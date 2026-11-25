package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.key.application.*;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.project.project.domain.*;
import com.dju.labflow.project.project.domain.repository.*;
import com.dju.labflow.run.log.application.RunLogCommand;
import com.dju.labflow.run.log.domain.*;
import com.dju.labflow.run.metric.application.RunMetricCommand;
import com.dju.labflow.run.parameter.application.RunParameterCommand;
import com.dju.labflow.run.run.application.*;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 프런트와 HTTP/H2 백엔드에서 로그인부터 키·SDK·비교·삭제·로그아웃까지 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LABFLOW_STUDIO_NODE", matches = ".+")
class StudioBrowserIntegrationTests {
    @LocalServerPort int port;
    @Autowired IdGenerator ids;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ProjectContentsRepository contents;
    @Autowired ProjectApiKeyService keys;
    @Autowired RunService runs;
    @Autowired RunRecordService records;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    UserId owner;

    @AfterEach
    void cleanup() {
        if (owner == null) return;
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            for (Project project :
                                    projects.findByCreatedBy(owner, 0, 100).items()) {
                                Project locked =
                                        projects.findByIdForUpdate(project.getId()).orElseThrow();
                                contents.deleteByProjectId(project.getId());
                                projects.delete(locked);
                            }
                            em.createQuery("delete from RefreshTokenEntity t where t.userId = :id")
                                    .setParameter("id", owner.value())
                                    .executeUpdate();
                            em.createQuery("delete from UserEntity u where u.id = :id")
                                    .setParameter("id", owner.value())
                                    .executeUpdate();
                        });
    }

    @Test
    void completeStudioUsesActualHttpAndDatabase() throws Exception {
        owner = UserId.of(ids.nextId());
        String email = "studio-" + owner.value() + "@example.com";
        users.save(
                User.restore(
                        owner,
                        email,
                        passwords.encode("studio-test-password"),
                        "실제 연구자",
                        Role.USER,
                        AccountStatus.ACTIVE));
        ProjectId project = ProjectId.of(ids.nextId());
        projects.save(
                Project.create(
                        project,
                        "Actual experiments",
                        "실제 서버 프로젝트",
                        owner,
                        ProjectVisibility.PRIVATE,
                        Instant.now()));
        IssueProjectApiKeyResult issued =
                keys.issue(owner, project, new IssueProjectApiKeyCommand("seed"));
        ProjectApiKeyPrincipal principal =
                new ProjectApiKeyPrincipal(issued.apiKeyId(), project, owner);
        for (int index = 0; index < 2; index++) {
            RunId run =
                    runs.create(
                            principal,
                            new RunCreateCommand(
                                    "training-" + index, Map.of("framework", "PyTorch")));
            records.parameters(
                    principal,
                    run,
                    new RunParameterCommand(
                            Map.of("optimizer", "Adam", "batch_size", index == 0 ? "32" : "64")));
            records.metrics(
                    principal,
                    run,
                    new RunMetricCommand(1, 0L, 0.0, Map.of("validation_score", 0.5), null));
            records.metrics(
                    principal,
                    run,
                    new RunMetricCommand(
                            1, 10L, 1.0, Map.of("validation_score", index == 0 ? 0.8 : 0.7), null));
            records.log(
                    principal,
                    run,
                    new RunLogCommand(
                            "actual-log-" + index, RunLogLevel.INFO, RunLogSource.USER, null));
            runs.complete(principal, run);
        }
        Path frontend = Path.of("..", "frontend").toAbsolutePath().normalize();
        Path output = Path.of("build", "studio-browser.log").toAbsolutePath();
        ProcessBuilder builder =
                new ProcessBuilder(System.getenv("LABFLOW_STUDIO_NODE"), "tests/studio.backend.ts");
        builder.directory(frontend.toFile());
        builder.environment().put("LABFLOW_BACKEND_URL", "http://127.0.0.1:" + port);
        builder.environment().put("LABFLOW_STUDIO_EMAIL", email);
        builder.environment().put("LABFLOW_STUDIO_PROJECT", project.value().toString());
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        boolean finished;
        try {
            finished = process.waitFor(150, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        String report = Files.readString(output, StandardCharsets.UTF_8);
        assertThat(finished).as("Studio browser timeout: %s", report).isTrue();
        assertThat(process.exitValue()).as("Studio browser: %s", report).isZero();
        assertThat(report).contains("PASS: complete Studio through actual HTTP");
    }
}
