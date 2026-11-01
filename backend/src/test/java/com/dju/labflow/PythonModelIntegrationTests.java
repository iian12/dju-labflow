package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.key.application.IssueProjectApiKeyCommand;
import com.dju.labflow.project.key.application.ProjectApiKeyService;
import com.dju.labflow.project.project.domain.*;
import com.dju.labflow.project.project.domain.repository.*;
import com.dju.labflow.run.log.domain.repository.RunLogRepository;
import com.dju.labflow.run.metric.domain.RunMetric;
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

/** 실제 숫자 분류 학습의 SDK 기록과 예외 발생 시 실패 전이를 HTTP/H2로 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LABFLOW_MODEL_PYTHON", matches = ".+")
class PythonModelIntegrationTests {
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
    void removeModelFixtures() {
        if (ownerId == null) return;
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            for (Project project :
                                    projects.findByCreatedBy(ownerId, 0, 100).items()) {
                                var locked =
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
    void trainRealClassifierAndRecordCompletionAndTrainingFailure() throws Exception {
        ownerId = UserId.of(ids.nextId());
        users.save(
                User.restore(
                        ownerId,
                        "model-test-" + ownerId.value() + "@example.com",
                        "unused-test-hash",
                        "Model SDK fixture",
                        Role.USER,
                        AccountStatus.ACTIVE));
        ProjectId projectId = ProjectId.of(ids.nextId());
        projects.save(
                Project.create(
                        projectId,
                        "model-sdk-test",
                        null,
                        ownerId,
                        ProjectVisibility.PRIVATE,
                        Instant.now()));
        String key =
                issuance.issue(ownerId, projectId, new IssueProjectApiKeyCommand("model-test"))
                        .rawApiKey();

        String completedOutput = executeModel(key, "digits-model-completed", false);
        assertThat(completedOutput).contains("epoch=20/20", "COMPLETED", "test_accuracy=");
        String failedOutput = executeModel(key, "digits-model-failed", true);
        assertThat(failedOutput).contains("Intentional training failure after epoch 1");
        var saved = runs.findByProjectId(projectId, 100);
        assertThat(saved).hasSize(2);
        Run completed =
                saved.stream()
                        .filter(run -> run.getName().equals("digits-model-completed"))
                        .findFirst()
                        .orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.getMetadata())
                .containsEntry("framework", "scikit-learn")
                .containsEntry("dataset", "digits")
                .containsEntry("device", "cpu");
        assertThat(parameters.findByRunId(completed.getId()))
                .hasSize(13)
                .extracting("key", "value")
                .contains(tuple("epochs", "20"), tuple("batch_size", "64"));
        List<RunMetric> points = metrics.findByRunId(completed.getId(), 100);
        assertThat(points)
                .hasSize(82)
                .allSatisfy(
                        metric -> {
                            assertThat(Double.isFinite(metric.getValue())).isTrue();
                            assertThat(metric.getStep()).isPositive();
                            assertThat(metric.getEpoch()).isPositive();
                        });
        assertThat(
                        points.stream()
                                .filter(metric -> metric.getKey().equals("test_accuracy"))
                                .findFirst()
                                .orElseThrow()
                                .getValue())
                .isGreaterThanOrEqualTo(0.85)
                .isLessThanOrEqualTo(1.0);
        assertThat(logs.findByRunId(completed.getId(), 100)).hasSize(22);
        assertThat(texts.findByRunId(completed.getId(), 100))
                .hasSize(2)
                .extracting("key")
                .containsExactlyInAnyOrder("classification_report", "prediction_samples");
        Run failed =
                saved.stream()
                        .filter(run -> run.getName().equals("digits-model-failed"))
                        .findFirst()
                        .orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getError())
                .isEqualTo("RuntimeError: Intentional training failure after epoch 1");
        assertThat(metrics.findByRunId(failed.getId(), 100)).hasSize(4);
        assertThat(failed.getEndedAt()).isNotNull();
    }

    private String executeModel(String key, String name, boolean fail) throws Exception {
        Path sdk = Path.of("..", "sdk").toAbsolutePath().normalize();
        List<String> command =
                new ArrayList<>(
                        List.of(
                                System.getenv("LABFLOW_MODEL_PYTHON"),
                                sdk.resolve("examples/train_digits.py").toString(),
                                "--name",
                                name,
                                "--epochs",
                                fail ? "2" : "20"));
        if (fail) command.addAll(List.of("--fail-after-epoch", "1"));
        Path output = Path.of("build", name + ".log").toAbsolutePath();
        Files.createDirectories(output.getParent());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(sdk.toFile());
        builder.environment().put("LABFLOW_API_KEY", key);
        builder.environment().put("LABFLOW_BASE_URL", "http://127.0.0.1:" + port);
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
        String report = Files.readString(output, StandardCharsets.UTF_8).replace(key, "[REDACTED]");
        assertThat(finished).as("Model process timed out: %s", report).isTrue();
        assertThat(process.exitValue()).as("Model training: %s", report).isEqualTo(fail ? 1 : 0);
        return report;
    }
}
