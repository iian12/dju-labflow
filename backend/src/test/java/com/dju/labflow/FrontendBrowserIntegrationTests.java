package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.dju.labflow.auth.application.verification.EmailVerificationCodeGenerator;
import com.dju.labflow.auth.application.verification.VerificationEmailSender;
import com.dju.labflow.auth.domain.RefreshTokenRepository;
import com.dju.labflow.project.project.domain.repository.*;
import com.dju.labflow.run.log.domain.repository.RunLogRepository;
import com.dju.labflow.run.metric.domain.repository.RunMetricRepository;
import com.dju.labflow.run.parameter.domain.repository.RunParameterRepository;
import com.dju.labflow.run.run.domain.RunStatus;
import com.dju.labflow.run.run.domain.repository.RunRepository;
import com.dju.labflow.run.systemmetric.domain.repository.RunSystemMetricRepository;
import com.dju.labflow.run.text.domain.repository.RunTextRepository;
import com.dju.labflow.user.domain.AccountStatus;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 테스트용 HTTP 백엔드와 실제 Chromium 폼을 연결한다. 외부 메일 전송만 대체한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LABFLOW_FRONTEND_NODE", matches = ".+")
class FrontendBrowserIntegrationTests {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ProjectContentsRepository contents;
    @Autowired RefreshTokenRepository tokens;
    @Autowired RunRepository runs;
    @Autowired RunParameterRepository parameters;
    @Autowired RunMetricRepository metrics;
    @Autowired RunLogRepository logs;
    @Autowired RunTextRepository texts;
    @Autowired RunSystemMetricRepository systemMetrics;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    @MockitoBean VerificationEmailSender sender;
    @MockitoBean EmailVerificationCodeGenerator codes;
    private final String email = "frontend-" + UUID.randomUUID() + "@example.com";

    @AfterEach
    void cleanBrowserFixtures() {
        users.findByEmail(email)
                .ifPresent(
                        user ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                status -> {
                                                    for (var project :
                                                            projects.findByCreatedBy(
                                                                            user.getId(), 0, 100)
                                                                    .items()) {
                                                        var locked =
                                                                projects.findByIdForUpdate(
                                                                                project.getId())
                                                                        .orElseThrow();
                                                        contents.deleteByProjectId(project.getId());
                                                        projects.delete(locked);
                                                    }
                                                    tokens.deleteAllByUserId(user.getId());
                                                    em.createQuery(
                                                                    "delete from EmailVerificationEntity e where e.id = :id")
                                                            .setParameter(
                                                                    "id", user.getId().value())
                                                            .executeUpdate();
                                                    em.createQuery(
                                                                    "delete from UserEntity u where u.id = :id")
                                                            .setParameter(
                                                                    "id", user.getId().value())
                                                            .executeUpdate();
                                                }));
    }

    @Test
    void browserFormsExerciseEveryApiAndPersistRecords() throws Exception {
        when(codes.generate()).thenReturn("ABC234");
        when(codes.normalize(anyString()))
                .thenAnswer(
                        invocation ->
                                new EmailVerificationCodeGenerator()
                                        .normalize(invocation.getArgument(0)));
        Path frontend = Path.of("..", "frontend-design").toAbsolutePath().normalize();
        assertThat(frontend.resolve("tests/backend.e2e.mjs")).exists();
        Path output = Path.of("build", "frontend-browser-integration.log").toAbsolutePath();
        Files.createDirectories(output.getParent());
        ProcessBuilder builder =
                new ProcessBuilder(
                        System.getenv("LABFLOW_FRONTEND_NODE"),
                        "--experimental-strip-types",
                        "tests/backend.e2e.mjs");
        builder.directory(frontend.toFile());
        builder.environment().put("LABFLOW_BACKEND_URL", "http://127.0.0.1:" + port);
        builder.environment().put("LABFLOW_FRONTEND_TEST_EMAIL", email);
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        boolean finished;
        try {
            finished = process.waitFor(120, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        String report = Files.readString(output, StandardCharsets.UTF_8);
        assertThat(finished).as("Browser process timed out: %s", report).isTrue();
        assertThat(process.exitValue()).as("Browser integration: %s", report).isZero();
        assertThat(report).contains("PASS: all 25 API operations");
        var user = users.findByEmail(email).orElseThrow();
        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(sender).send(email, "ABC234");
        var remaining = projects.findByCreatedBy(user.getId(), 0, 100).items();
        assertThat(remaining).hasSize(1);
        var project = remaining.getFirst();
        assertThat(project.getName()).isEqualTo("Updated browser project");
        var saved = runs.findByProjectId(project.getId(), 100);
        assertThat(saved)
                .hasSize(2)
                .extracting("status")
                .containsExactlyInAnyOrder(RunStatus.COMPLETED, RunStatus.FAILED);
        var completed =
                saved.stream()
                        .filter(run -> run.getStatus() == RunStatus.COMPLETED)
                        .findFirst()
                        .orElseThrow();
        assertThat(parameters.findByRunId(completed.getId())).hasSize(2);
        assertThat(metrics.findByRunId(completed.getId(), 100)).hasSize(2);
        assertThat(logs.findByRunId(completed.getId(), 100)).hasSize(1);
        assertThat(texts.findByRunId(completed.getId(), 100)).hasSize(1);
        assertThat(systemMetrics.findByRunId(completed.getId(), 100)).hasSize(1);
    }
}
