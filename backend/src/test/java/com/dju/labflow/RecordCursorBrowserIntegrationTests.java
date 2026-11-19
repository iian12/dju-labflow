package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.key.application.*;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.project.project.domain.*;
import com.dju.labflow.project.project.domain.repository.*;
import com.dju.labflow.run.log.application.RunLogCommand;
import com.dju.labflow.run.log.domain.*;
import com.dju.labflow.run.log.domain.repository.RunLogRepository;
import com.dju.labflow.run.metric.application.RunMetricCommand;
import com.dju.labflow.run.run.application.*;
import com.dju.labflow.run.run.domain.*;
import com.dju.labflow.run.systemmetric.application.RunSystemMetricCommand;
import com.dju.labflow.run.systemmetric.domain.GpuMetric;
import com.dju.labflow.run.text.application.RunTextCommand;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** frontend/의 실제 브라우저를 HTTP/H2 서버에 연결해 1000개 초과 기록 탐색과 최신 갱신을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LABFLOW_RECORDS_NODE", matches = ".+")
class RecordCursorBrowserIntegrationTests {
    @LocalServerPort int port;
    @Autowired IdGenerator ids;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ProjectContentsRepository contents;
    @Autowired ProjectApiKeyService issuance;
    @Autowired RunService runs;
    @Autowired RunRecordService records;
    @Autowired RunLogRepository logs;
    @Autowired AccessTokenProvider tokens;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    private UserId owner;
    private ProjectId project;

    @AfterEach
    void cleanup() {
        if (owner == null) return;
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            if (project != null)
                                projects.findByIdForUpdate(project)
                                        .ifPresent(
                                                locked -> {
                                                    contents.deleteByProjectId(project);
                                                    projects.delete(locked);
                                                });
                            em.createQuery("delete from UserEntity u where u.id=:id")
                                    .setParameter("id", owner.value())
                                    .executeUpdate();
                        });
    }

    @Test
    void browserReadsOlderPagesAndFreshRecordsFromActualBackend() throws Exception {
        owner = UserId.of(ids.nextId());
        User user =
                users.save(
                        User.restore(
                                owner,
                                "records-" + owner.value() + "@example.com",
                                "unused-hash",
                                "reader",
                                Role.USER,
                                AccountStatus.ACTIVE));
        project = ProjectId.of(ids.nextId());
        projects.save(
                Project.create(
                        project,
                        "Record pagination",
                        null,
                        owner,
                        ProjectVisibility.PRIVATE,
                        Instant.now()));
        var key = issuance.issue(owner, project, new IssueProjectApiKeyCommand("records-test"));
        var principal = new ProjectApiKeyPrincipal(key.apiKeyId(), project, owner);
        RunId run = runs.create(principal, new RunCreateCommand("Long training", Map.of()));
        Instant when = Instant.now().minusSeconds(3600);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            for (int i = 0; i < 1005; i++)
                                records.log(
                                        principal,
                                        run,
                                        new RunLogCommand(
                                                "log-" + i,
                                                RunLogLevel.INFO,
                                                RunLogSource.STDOUT,
                                                when.plusMillis(i)));
                            records.metrics(
                                    principal,
                                    run,
                                    new RunMetricCommand(
                                            1,
                                            1005L,
                                            10.0,
                                            Map.of("loss", 0.125, "accuracy", 0.9),
                                            when));
                            records.text(
                                    principal,
                                    run,
                                    new RunTextCommand("prediction", "실제 서버 텍스트 🐈", 1005L, when));
                            records.systemMetric(
                                    principal,
                                    run,
                                    new RunSystemMetricCommand(
                                            25.0,
                                            1024L,
                                            2048L,
                                            List.of(new GpuMetric(0, 50.0, 64L, 128L)),
                                            10.0,
                                            when));
                        });
        String cookie = tokens.createAccessToken(user.getId(), user.getRole());
        Path frontend = Path.of("..", "frontend").toAbsolutePath().normalize();
        Path output = Path.of("build", "record-cursor-browser.log").toAbsolutePath();
        Files.createDirectories(output.getParent());
        ProcessBuilder builder =
                new ProcessBuilder(
                        System.getenv("LABFLOW_RECORDS_NODE"), "tests/records.backend.ts");
        builder.directory(frontend.toFile());
        builder.environment().put("LABFLOW_BACKEND_URL", "http://127.0.0.1:" + port);
        builder.environment().put("LABFLOW_RECORDS_PROJECT", project.value().toString());
        builder.environment().put("LABFLOW_RECORDS_RUN", run.value().toString());
        builder.environment().put("LABFLOW_RECORDS_KEY", key.rawApiKey());
        builder.environment().put("LABFLOW_RECORDS_COOKIE", cookie);
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        boolean finished;
        try {
            finished = process.waitFor(90, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        String report =
                Files.readString(output, StandardCharsets.UTF_8)
                        .replace(key.rawApiKey(), "[REDACTED]")
                        .replace(cookie, "[REDACTED]");
        assertThat(finished).as("Record browser timeout: %s", report).isTrue();
        assertThat(process.exitValue()).as("Record browser: %s", report).isZero();
        assertThat(report).contains("PASS: record cursors and latest through actual HTTP");
        assertThat(logs.findPage(run, new RecordPageQuery(5, null, true)).items())
                .extracting(RunLog::getMessage)
                .contains("new-live-log", "arrived-by-poll");
    }
}
