package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.key.domain.*;
import com.dju.labflow.project.project.application.*;
import com.dju.labflow.project.project.domain.*;
import com.dju.labflow.run.artifact.application.LocalArtifactService;
import com.dju.labflow.run.artifact.domain.*;
import com.dju.labflow.run.artifact.domain.repository.RunArtifactRepository;
import com.dju.labflow.run.metric.application.RunMetricCommand;
import com.dju.labflow.run.run.application.*;
import com.dju.labflow.run.run.domain.*;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        properties = {
            "labflow.artifacts.max-file-bytes=16",
            "labflow.artifacts.max-project-bytes=12"
        })
@ActiveProfiles("test")
class LocalArtifactUploadTests {
    @TempDir static Path directory;

    @DynamicPropertySource
    static void files(DynamicPropertyRegistry properties) {
        properties.add("labflow.artifacts.directory", () -> directory.toString());
    }

    @Autowired LocalArtifactService files;

    @Autowired
    com.dju.labflow.run.artifact.application.LocalArtifactTransactions uploadTransactions;

    @Autowired RunAccessPolicy access;
    @Autowired com.dju.labflow.sdk.idempotency.IdempotencyStore ledger;
    @Autowired com.dju.labflow.project.key.application.ProjectApiKeyService issuance;
    @Autowired RunArtifactRepository artifacts;
    @Autowired ProjectService projects;
    @Autowired RunService runs;
    @Autowired RunRecordService records;
    @Autowired UserRepository users;
    @Autowired IdGenerator ids;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    UserId owner;
    ProjectId project;
    ProjectApiKeyPrincipal principal;
    RunId run;

    @BeforeEach
    void fixture() {
        owner = UserId.of(ids.nextId());
        users.save(
                User.restore(
                        owner,
                        "upload-" + owner.value() + "@example.com",
                        "unused",
                        "Upload",
                        Role.USER,
                        AccountStatus.ACTIVE));
        project =
                projects.create(
                        owner, new ProjectCreateCommand("uploads", "", ProjectVisibility.PRIVATE));
        principal =
                new ProjectApiKeyPrincipal(
                        issuance.issue(
                                        owner,
                                        project,
                                        new com.dju.labflow.project.key.application
                                                .IssueProjectApiKeyCommand("uploads"))
                                .apiKeyId(),
                        project,
                        owner);
        run = runs.create(principal, new RunCreateCommand("uploading", Map.of()));
    }

    @AfterEach
    void cleanup() {
        try {
            projects.delete(owner, project, projects.get(owner, project).getVersion());
        } catch (com.dju.labflow.project.project.exception.ProjectNotFoundException deleted) {
        }
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                em.createQuery("delete from UserEntity u where u.id = :id")
                                        .setParameter("id", owner.value())
                                        .executeUpdate());
    }

    @Test
    void lostCompletionAcknowledgmentKeepsTheCommittedFileAndResponse() throws Exception {
        com.dju.labflow.run.artifact.application.LocalArtifactTransactions interrupted =
                org.mockito.Mockito.mock(
                        com.dju.labflow.run.artifact.application.LocalArtifactTransactions.class,
                        org.mockito.AdditionalAnswers.delegatesTo(uploadTransactions));
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            uploadTransactions.complete(
                                    invocation.getArgument(0),
                                    invocation.getArgument(1),
                                    invocation.getArgument(2),
                                    invocation.getArgument(3));
                            throw new IllegalStateException(
                                    "Simulated lost commit acknowledgement");
                        })
                .when(interrupted)
                .complete(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        LocalArtifactService recovering =
                new LocalArtifactService(
                        access, artifacts, interrupted, directory.toString(), 16, 12);
        com.dju.labflow.sdk.idempotency.IdempotencyStore.Claim claim =
                ledger.claim(principal, "d".repeat(64), "e".repeat(64));
        byte[] payload = new byte[] {1, 2, 3, 4};
        RunArtifact result =
                recovering.upload(
                        principal,
                        run,
                        "committed.bin",
                        ArtifactKind.OTHER,
                        claim,
                        payload.length,
                        new ByteArrayInputStream(payload));
        assertThat(result.getStatus()).isEqualTo(ArtifactStatus.READY);
        assertThat(Files.readAllBytes(directory.resolve(result.getId().value().toString())))
                .isEqualTo(payload);
        assertThat(artifacts.findReadyByRunId(run, 10)).hasSize(1);
        assertThat(ledger.claim(principal, "d".repeat(64), "e".repeat(64)).replay()).isTrue();
    }

    @Test
    void slowUploadDoesNotBlockOtherRunsAndReservesCapacity() throws Exception {
        RunId other = runs.create(principal, new RunCreateCommand("other", Map.of()));
        try (PausedInput input = new PausedInput();
                ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<RunArtifact> upload = pool.submit(() -> upload(run, "first", 8, input));
            try {
                assertThat(input.entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(artifacts.findReadyByRunId(run, 10)).isEmpty();
                pool.submit(
                                () -> {
                                    runs.heartbeat(principal, other);
                                    records.metrics(
                                            principal,
                                            other,
                                            new RunMetricCommand(
                                                    null, 1L, 1.0, Map.of("loss", 0.5), null));
                                })
                        .get(3, TimeUnit.SECONDS);
                assertThatThrownBy(
                                () ->
                                        upload(
                                                other,
                                                "over",
                                                5,
                                                new ByteArrayInputStream(new byte[5])))
                        .isInstanceOf(ResponseStatusException.class)
                        .satisfies(
                                error ->
                                        assertThat(
                                                        ((ResponseStatusException) error)
                                                                .getStatusCode()
                                                                .value())
                                                .isEqualTo(413));
                assertThatThrownBy(
                                () ->
                                        upload(
                                                run,
                                                "first",
                                                8,
                                                new ByteArrayInputStream(new byte[8])))
                        .isInstanceOf(com.dju.labflow.sdk.idempotency.IdempotencyException.class);
            } finally {
                input.release.countDown();
            }
            RunArtifact ready = upload.get(5, TimeUnit.SECONDS);
            assertThat(ready.getStatus()).isEqualTo(ArtifactStatus.READY);
            assertThat(Files.size(directory.resolve(ready.getId().value().toString())))
                    .isEqualTo(8);
            assertThat(upload(run, "first", 8, new ByteArrayInputStream(new byte[8])).getId())
                    .isEqualTo(ready.getId());
            assertThatThrownBy(
                            () ->
                                    upload(
                                            run,
                                            "first",
                                            8,
                                            new ByteArrayInputStream(
                                                    "different".substring(0, 8).getBytes())))
                    .isInstanceOf(com.dju.labflow.sdk.idempotency.IdempotencyException.class);
        }
    }

    @Test
    void failedTransferReleasesCapacityAndRequestId() throws Exception {
        assertThatThrownBy(() -> upload(run, "retry", 8, new ByteArrayInputStream(new byte[2])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(artifacts.findByRunId(run, 100)).isEmpty();
        RunArtifact ready =
                upload(run, "retry-new-operation", 12, new ByteArrayInputStream(new byte[12]));
        assertThat(ready.getStatus()).isEqualTo(ArtifactStatus.READY);
        try (Stream<Path> paths = Files.list(directory)) {
            assertThat(paths.filter(path -> path.toString().endsWith(".part"))).isEmpty();
        }
    }

    @Test
    void projectDeletionDuringTransferLeavesNoFileOrReservation() throws Exception {
        try (PausedInput input = new PausedInput();
                ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<RunArtifact> upload = pool.submit(() -> upload(run, "deleted", 8, input));
            try {
                assertThat(input.entered.await(5, TimeUnit.SECONDS)).isTrue();
                pool.submit(
                                () ->
                                        projects.delete(
                                                owner,
                                                project,
                                                projects.get(owner, project).getVersion()))
                        .get(3, TimeUnit.SECONDS);
            } finally {
                input.release.countDown();
            }
            assertThatThrownBy(() -> upload.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            assertThat(artifacts.findByRunId(run, 10)).isEmpty();
            try (Stream<Path> paths = Files.list(directory)) {
                assertThat(paths).isEmpty();
            }
        }
    }

    @Test
    void runCompletionDuringTransferRejectsPublicationAndReleasesReservation() throws Exception {
        try (PausedInput input = new PausedInput();
                ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<RunArtifact> upload = pool.submit(() -> upload(run, "finished", 8, input));
            try {
                assertThat(input.entered.await(5, TimeUnit.SECONDS)).isTrue();
                pool.submit(() -> runs.complete(principal, run)).get(3, TimeUnit.SECONDS);
            } finally {
                input.release.countDown();
            }
            assertThatThrownBy(() -> upload.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            assertThat(artifacts.findByRunId(run, 10)).isEmpty();
            try (Stream<Path> paths = Files.list(directory)) {
                assertThat(paths).isEmpty();
            }
        }
    }

    private RunArtifact upload(RunId runId, String request, long size, InputStream input)
            throws IOException {
        return files.upload(
                principal,
                runId,
                "weights.bin",
                ArtifactKind.MODEL,
                ledger.claim(
                        principal,
                        com.dju.labflow.sdk.idempotency.IdempotencyFilter.hash(
                                (principal.apiKeyId() + "/" + runId + "/" + request)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        com.dju.labflow.sdk.idempotency.IdempotencyFilter.hash(
                                Long.toString(size)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                size,
                input);
    }

    @Test
    void expiresAbandonedReservationsAndRemovesTheirFilesBeforeReusingCapacity() throws Exception {
        RunArtifactId id = RunArtifactId.of(ids.nextId());
        Instant old = Instant.now().minusSeconds(172800);
        artifacts.save(
                RunArtifact.create(
                        id,
                        run,
                        "weights.bin",
                        ArtifactKind.MODEL,
                        "application/octet-stream",
                        12L,
                        "local",
                        id.value().toString(),
                        null,
                        old,
                        old.plusSeconds(86400)));
        Files.createDirectories(directory);
        Files.write(directory.resolve(id.value() + ".part"), new byte[4]);
        Files.write(directory.resolve(id.value().toString()), new byte[4]);
        assertThat(upload(run, "abandoned", 12, new ByteArrayInputStream(new byte[12])).getStatus())
                .isEqualTo(ArtifactStatus.READY);
        assertThat(artifacts.findById(id)).isEmpty();
        assertThat(directory.resolve(id.value() + ".part")).doesNotExist();
        assertThat(directory.resolve(id.value().toString())).doesNotExist();
    }

    private static class PausedInput extends ByteArrayInputStream {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

        PausedInput() {
            super(new byte[8]);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("Upload was not released");
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
            return super.read(buffer, offset, length);
        }

        @Override
        public void close() {
            release.countDown();
        }
    }
}
