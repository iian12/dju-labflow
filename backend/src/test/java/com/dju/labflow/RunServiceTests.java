package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.project.key.domain.*;
import com.dju.labflow.project.project.domain.*;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import com.dju.labflow.run.log.application.RunLogCommand;
import com.dju.labflow.run.log.domain.*;
import com.dju.labflow.run.log.domain.repository.RunLogRepository;
import com.dju.labflow.run.metric.application.RunMetricCommand;
import com.dju.labflow.run.metric.domain.repository.RunMetricRepository;
import com.dju.labflow.run.parameter.application.RunParameterCommand;
import com.dju.labflow.run.parameter.domain.repository.RunParameterRepository;
import com.dju.labflow.run.run.application.*;
import com.dju.labflow.run.run.domain.*;
import com.dju.labflow.run.run.domain.repository.RunRepository;
import com.dju.labflow.run.run.exception.*;
import com.dju.labflow.run.run.infrastructure.persistence.RunEntity;
import com.dju.labflow.run.systemmetric.application.RunSystemMetricCommand;
import com.dju.labflow.run.systemmetric.domain.GpuMetric;
import com.dju.labflow.run.systemmetric.domain.repository.RunSystemMetricRepository;
import com.dju.labflow.run.systemmetric.infrastructure.persistence.RunSystemMetricEntity;
import com.dju.labflow.run.text.application.RunTextCommand;
import com.dju.labflow.run.text.domain.repository.RunTextRepository;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 서비스 트랜잭션의 커밋/롤백, 영속성 및 실행별 접근 제한을 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
class RunServiceTests {
    private static final AtomicLong IDS = new AtomicLong(100000L);
    private final List<Long> owners = new ArrayList<>();
    @Autowired RunService runService;
    @Autowired RunRecordService records;
    @Autowired RunRepository runs;
    @Autowired RunParameterRepository parameters;
    @Autowired RunMetricRepository metrics;
    @Autowired RunLogRepository logs;
    @Autowired RunTextRepository texts;
    @Autowired RunSystemMetricRepository systemMetrics;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach
    void cleanCommittedFixtures() {
        if (owners.isEmpty()) return;
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            var entities =
                                    em.createQuery(
                                                    "select r from RunEntity r where r.createdBy in :ids",
                                                    RunEntity.class)
                                            .setParameter("ids", owners)
                                            .getResultList();
                            var ids = entities.stream().map(RunEntity::getId).toList();
                            if (!ids.isEmpty()) {
                                for (String entity :
                                        List.of(
                                                "RunParameterEntity",
                                                "RunMetricEntity",
                                                "RunLogEntity",
                                                "RunTextEntity")) {
                                    em.createQuery(
                                                    "delete from "
                                                            + entity
                                                            + " r where r.runId in :ids")
                                            .setParameter("ids", ids)
                                            .executeUpdate();
                                }
                                em.createQuery(
                                                "select r from RunSystemMetricEntity r where r.runId in :ids",
                                                RunSystemMetricEntity.class)
                                        .setParameter("ids", ids)
                                        .getResultList()
                                        .forEach(em::remove);
                                em.flush();
                                entities.forEach(em::remove);
                                em.flush();
                            }
                            em.createQuery("delete from ProjectEntity p where p.createdBy in :ids")
                                    .setParameter("ids", owners)
                                    .executeUpdate();
                            em.createQuery("delete from UserEntity u where u.id in :ids")
                                    .setParameter("ids", owners)
                                    .executeUpdate();
                        });
    }

    @Test
    void completeLifecyclePersistsAllRecordTypesAndMetadata() {
        var principal = principal();
        RunId id = create(principal);
        records.parameters(principal, id, new RunParameterCommand(Map.of("optimizer", "adam")));
        records.metrics(principal, id, metric(0.125));
        records.log(
                principal,
                id,
                new RunLogCommand("학습 시작\n", RunLogLevel.INFO, RunLogSource.USER, null));
        records.log(
                principal, id, new RunLogCommand("", RunLogLevel.INFO, RunLogSource.STDOUT, null));
        records.text(principal, id, new RunTextCommand("prediction", "고양이\ncat", 1L, null));
        records.systemMetric(
                principal,
                id,
                new RunSystemMetricCommand(
                        25.0,
                        1024L,
                        8192L,
                        List.of(
                                new GpuMetric(1, 10.0, 1024L, 4096L),
                                new GpuMetric(0, 50.0, 2048L, 4096L)),
                        1.0,
                        null));
        runService.complete(principal, id);

        Run saved = runService.get(principal, id);
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(saved.getMetadata()).containsEntry("framework", "pytorch");
        assertThat(saved.getCreatedBy()).isEqualTo(principal.createdBy());
        assertThat(parameters.findByRunId(id))
                .singleElement()
                .satisfies(p -> assertThat(p.getValue()).isEqualTo("adam"));
        assertThat(metrics.findByRunId(id, 10))
                .singleElement()
                .satisfies(m -> assertThat(m.getValue()).isEqualTo(0.125));
        assertThat(logs.findByRunId(id, 10))
                .extracting(l -> l.getSource())
                .containsExactly(RunLogSource.USER, RunLogSource.STDOUT);
        assertThat(texts.findByRunId(id, 10))
                .singleElement()
                .satisfies(t -> assertThat(t.getText()).isEqualTo("고양이\ncat"));
        assertThat(systemMetrics.findByRunId(id, 10))
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.getGpus())
                                    .extracting(GpuMetric::index)
                                    .containsExactly(0, 1);
                            assertThat(s.getRamTotal()).isEqualTo(8192L);
                        });
        Instant endedAt = saved.getEndedAt();
        runService.complete(principal, id);
        assertThat(runService.get(principal, id).getEndedAt()).isEqualTo(endedAt);
    }

    @Test
    void failedRunPreservesReasonAndRejectsConflictingTerminalTransition() {
        var principal = principal();
        RunId id = create(principal);
        runService.fail(principal, id, new RunFailCommand("out of memory"));
        runService.fail(principal, id, new RunFailCommand("out of memory"));
        assertThat(runService.get(principal, id).getError()).isEqualTo("out of memory");
        assertThatThrownBy(() -> runService.complete(principal, id))
                .isInstanceOf(RunStateConflictException.class);
    }

    @Test
    void endedRunRejectsEveryRecordType() {
        var p = principal();
        RunId id = create(p);
        runService.complete(p, id);
        assertThatThrownBy(
                        () -> records.parameters(p, id, new RunParameterCommand(Map.of("a", "b"))))
                .isInstanceOf(RunStateConflictException.class);
        assertThatThrownBy(() -> records.metrics(p, id, metric(0.1)))
                .isInstanceOf(RunStateConflictException.class);
        assertThatThrownBy(
                        () ->
                                records.log(
                                        p,
                                        id,
                                        new RunLogCommand(
                                                "x", RunLogLevel.INFO, RunLogSource.USER, null)))
                .isInstanceOf(RunStateConflictException.class);
        assertThatThrownBy(() -> records.text(p, id, new RunTextCommand("x", "y", null, null)))
                .isInstanceOf(RunStateConflictException.class);
        assertThatThrownBy(
                        () ->
                                records.systemMetric(
                                        p,
                                        id,
                                        new RunSystemMetricCommand(
                                                1.0, null, null, null, null, null)))
                .isInstanceOf(RunStateConflictException.class);
    }

    @Test
    void publicProjectDoesNotAllowAnotherProjectsKeyToReadWriteOrEndRuns() {
        var owner = principal();
        RunId id = create(owner);
        var other = principal();
        assertThatThrownBy(() -> runService.get(other, id))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> runService.complete(other, id))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> runService.fail(other, id, new RunFailCommand("bad")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(
                        () ->
                                records.parameters(
                                        other, id, new RunParameterCommand(Map.of("a", "b"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> records.metrics(other, id, metric(0.1)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(
                        () ->
                                records.log(
                                        other,
                                        id,
                                        new RunLogCommand(
                                                "x", RunLogLevel.INFO, RunLogSource.USER, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> records.text(other, id, new RunTextCommand("x", "y", null, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(
                        () ->
                                records.systemMetric(
                                        other,
                                        id,
                                        new RunSystemMetricCommand(
                                                1.0, null, null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(metrics.findByRunId(id, 10)).isEmpty();
    }

    @Test
    void parameterRetriesAreIdempotentAndChangesRollBackTheWholeBatch() {
        var p = principal();
        RunId id = create(p);
        var first = records.parameters(p, id, new RunParameterCommand(Map.of("optimizer", "adam")));
        assertThat(records.parameters(p, id, new RunParameterCommand(Map.of("optimizer", "adam"))))
                .isEqualTo(first);
        var changes = new LinkedHashMap<String, String>();
        changes.put("new_parameter", "new value");
        changes.put("optimizer", "sgd");
        assertThatThrownBy(() -> records.parameters(p, id, new RunParameterCommand(changes)))
                .isInstanceOf(RunStateConflictException.class);
        assertThat(parameters.findByRunId(id)).hasSize(1);
        assertThat(parameters.findByRunIdAndKey(id, "optimizer").orElseThrow().getValue())
                .isEqualTo("adam");
    }

    @Test
    void parameterLimitAppliesAcrossBatches() {
        var p = principal();
        RunId id = create(p);
        var batch = new HashMap<String, String>();
        for (int i = 0; i < 100; i++) batch.put("p" + i, "v");
        records.parameters(p, id, new RunParameterCommand(batch));
        assertThatThrownBy(
                        () ->
                                records.parameters(
                                        p, id, new RunParameterCommand(Map.of("extra", "v"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(parameters.findByRunId(id)).hasSize(100);
    }

    @Test
    void invalidMetricRollsBackEarlierInsertsInTheSameBatch() {
        var p = principal();
        RunId id = create(p);
        var batch = new LinkedHashMap<String, Double>();
        batch.put("valid", 1.0);
        batch.put("invalid", Double.NaN);
        assertThatThrownBy(
                        () ->
                                records.metrics(
                                        p, id, new RunMetricCommand(null, 0L, null, batch, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(metrics.findByRunId(id, 10)).isEmpty();
    }

    @Test
    void staleRunCannotReplaceCommittedTerminalState() {
        var p = principal();
        RunId id = create(p);
        Run stale = runs.findById(id).orElseThrow();
        runService.complete(p, id);
        stale.fail("late failure", Instant.now());
        assertThatThrownBy(() -> runs.update(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(runService.get(p, id).getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void queriesHaveBoundedDeterministicOrdering() {
        var p = principal();
        RunId id = create(p);
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        records.metrics(
                p,
                id,
                new RunMetricCommand(null, 2L, null, Map.of("loss", 0.2), at.plusSeconds(1)));
        records.metrics(p, id, new RunMetricCommand(null, 1L, null, Map.of("loss", 0.3), at));
        assertThat(metrics.findByRunId(id, 1))
                .singleElement()
                .satisfies(m -> assertThat(m.getStep()).isEqualTo(1L));
        assertThatThrownBy(() -> metrics.findByRunId(id, 0))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runs.findByProjectId(p.projectId(), 1001))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appendWaitingForTerminationCannotWriteAfterCompletion() throws Exception {
        var p = principal();
        RunId id = create(p);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var appendStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var finish =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactions)
                                            .executeWithoutResult(
                                                    status -> {
                                                        Run run =
                                                                runs.findByIdForUpdate(id)
                                                                        .orElseThrow();
                                                        run.complete(Instant.now());
                                                        runs.update(run);
                                                        locked.countDown();
                                                        await(release);
                                                    }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var append =
                        executor.submit(
                                () -> {
                                    appendStarted.countDown();
                                    return records.metrics(p, id, metric(0.1));
                                });
                assertThat(appendStarted.await(10, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                finish.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> append.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(RunStateConflictException.class);
            } finally {
                release.countDown();
            }
        }
        assertThat(metrics.findByRunId(id, 10)).isEmpty();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private RunMetricCommand metric(double value) {
        return new RunMetricCommand(1, 1L, 1.0, Map.of("loss", value), null);
    }

    private RunId create(ProjectApiKeyPrincipal principal) {
        return runService.create(
                principal, new RunCreateCommand("training", Map.of("framework", "pytorch")));
    }

    private ProjectApiKeyPrincipal principal() {
        long id = IDS.incrementAndGet();
        owners.add(id);
        User user =
                users.save(
                        User.restore(
                                UserId.of(id),
                                "run" + id + "@example.test",
                                "encoded",
                                "test",
                                Role.USER,
                                AccountStatus.ACTIVE));
        Project project =
                projects.save(
                        Project.create(
                                ProjectId.of(id),
                                "project",
                                null,
                                user.getId(),
                                ProjectVisibility.PUBLIC,
                                Instant.now()));
        return new ProjectApiKeyPrincipal(ProjectApiKeyId.of(id), project.getId(), user.getId());
    }
}
