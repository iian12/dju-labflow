package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.key.application.IssueProjectApiKeyCommand;
import com.dju.labflow.project.key.application.ProjectApiKeyService;
import com.dju.labflow.project.project.domain.*;
import com.dju.labflow.project.project.domain.repository.*;
import com.dju.labflow.run.log.domain.*;
import com.dju.labflow.run.log.domain.repository.RunLogRepository;
import com.dju.labflow.run.metric.domain.repository.RunMetricRepository;
import com.dju.labflow.run.parameter.domain.repository.RunParameterRepository;
import com.dju.labflow.run.run.domain.*;
import com.dju.labflow.run.run.domain.repository.RunRepository;
import com.dju.labflow.run.text.domain.repository.RunTextRepository;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 임의 포트 HTTP 서버에 Python SDK를 연결하고 저장 결과까지 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LABFLOW_PYTHON", matches = ".+")
class PythonSdkIntegrationTests {
    @LocalServerPort int port;
    @Autowired IdGenerator ids;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ProjectContentsRepository contents;
    @Autowired ProjectApiKeyService issuance;
    @Autowired RunRepository runs;
    @Autowired RunParameterRepository parameters;
    @Autowired RunMetricRepository metrics;
    @Autowired RunLogRepository logs;
    @Autowired RunTextRepository texts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    private UserId ownerId;

    @AfterEach
    void removeCommittedFixtures() {
        if (ownerId == null) return;
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            for (Project project :
                                    projects.findByCreatedBy(ownerId, 0, 100).items()) {
                                Project locked =
                                        projects.findByIdForUpdate(project.getId()).orElseThrow();
                                contents.deleteByProjectId(project.getId());
                                projects.delete(locked);
                            }
                            em.createQuery("delete from UserEntity u where u.id = :id")
                                    .setParameter("id", ownerId.value())
                                    .executeUpdate();
                        });
    }

    @Test
    void pythonSdkWritesThroughRealHttpAndPersistsRecords() throws Exception {
        ownerId = UserId.of(ids.nextId());
        users.save(
                User.restore(
                        ownerId,
                        "python-sdk-" + ownerId.value() + "@example.com",
                        "unused-test-hash",
                        "Python SDK fixture",
                        Role.USER,
                        AccountStatus.ACTIVE));
        ProjectId projectId = project("python-sdk-test");
        String key =
                issuance.issue(ownerId, projectId, new IssueProjectApiKeyCommand("test"))
                        .rawApiKey();
        String otherKey =
                issuance.issue(
                                ownerId,
                                project("other-sdk-test"),
                                new IssueProjectApiKeyCommand("other"))
                        .rawApiKey();

        Path sdk = Path.of("..", "sdk").toAbsolutePath().normalize();
        assertThat(sdk.resolve("tests/integration/test_backend.py")).exists();
        Path output = Path.of("build", "python-sdk-integration.log").toAbsolutePath();
        Files.createDirectories(output.getParent());
        ProcessBuilder builder =
                new ProcessBuilder(
                        System.getenv("LABFLOW_PYTHON"),
                        "-m",
                        "pytest",
                        "-q",
                        "tests/integration/test_backend.py");
        builder.directory(sdk.toFile());
        builder.environment().put("LABFLOW_TEST_BACKEND_URL", "http://127.0.0.1:" + port);
        builder.environment().put("LABFLOW_TEST_API_KEY", key);
        builder.environment().put("LABFLOW_TEST_OTHER_API_KEY", otherKey);
        builder.environment().put("PYTHONUTF8", "1");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        boolean finished;
        try {
            finished = process.waitFor(60, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        String report =
                Files.readString(output, StandardCharsets.UTF_8)
                        .replace(key, "[REDACTED]")
                        .replace(otherKey, "[REDACTED]");
        assertThat(finished).as("Python SDK test process timed out: %s", report).isTrue();
        assertThat(process.exitValue()).as("Python SDK HTTP tests: %s", report).isZero();
        assertThat(report).contains("4 passed");

        List<Run> saved = runs.findByProjectId(projectId, 100);
        assertThat(saved).hasSize(5).allSatisfy(run -> assertThat(run.getEndedAt()).isNotNull());
        Run completed = named(saved, "python-sdk-completed");
        assertThat(completed.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.getMetadata())
                .containsEntry("framework", "python")
                .containsEntry("seed", "42");
        assertThat(parameters.findByRunId(completed.getId()))
                .extracting("key", "value")
                .containsExactlyInAnyOrder(
                        tuple("optimizer", "adam"),
                        tuple("batch", "32"),
                        tuple("lr", "0.001"),
                        tuple("amp", "true"));
        assertThat(metrics.findByRunId(completed.getId(), 100))
                .hasSize(4)
                .extracting("key", "value", "step", "epoch")
                .containsExactlyInAnyOrder(
                        tuple("loss", 0.5, 0L, 0),
                        tuple("accuracy", 0.75, 0L, 0),
                        tuple("loss", 0.25, 1L, 1),
                        tuple("accuracy", 0.9, 1L, 1));
        assertThat(metrics.findByRunId(completed.getId(), 100))
                .allSatisfy(
                        metric -> assertThat(metric.getElapsedTime()).isGreaterThanOrEqualTo(0));
        assertThat(logs.findByRunId(completed.getId(), 100))
                .extracting("message", "level", "source")
                .containsExactlyInAnyOrder(
                        tuple("학습 시작", RunLogLevel.INFO, RunLogSource.USER),
                        tuple("", RunLogLevel.DEBUG, RunLogSource.STDOUT));
        assertThat(texts.findByRunId(completed.getId(), 100))
                .extracting("key", "text", "step")
                .containsExactlyInAnyOrder(
                        tuple("prediction", "고양이 🐈\n원문 유지", 1L), tuple("empty", "", null));
        assertThat(
                        texts.findByRunId(completed.getId(), 100).stream()
                                .filter(text -> text.getKey().equals("prediction"))
                                .findFirst()
                                .orElseThrow()
                                .getRecordedAt())
                .isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        Run failed = named(saved, "python-sdk-failed");
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getError()).isEqualTo("ValueError: training failed");
        Run manual = named(saved, "python-sdk-manual-failed");
        assertThat(manual.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(manual.getError()).isEqualTo("out of memory");
        assertThat(parameters.findByRunId(named(saved, "python-sdk-conflict").getId()))
                .extracting("value")
                .containsExactly("adam");
        assertThat(named(saved, "python-sdk-permissions").getStatus())
                .isEqualTo(RunStatus.COMPLETED);
    }

    private ProjectId project(String name) {
        ProjectId projectId = ProjectId.of(ids.nextId());
        projects.save(
                Project.create(
                        projectId, name, null, ownerId, ProjectVisibility.PRIVATE, Instant.now()));
        return projectId;
    }

    private Run named(List<Run> saved, String name) {
        return saved.stream().filter(run -> run.getName().equals(name)).findFirst().orElseThrow();
    }
}
