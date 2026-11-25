package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.repository.*;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LABFLOW_ADMIN_NODE", matches = ".+")
class AdminApiBrowserIntegrationTests {
    @LocalServerPort int port;
    @Autowired IdGenerator ids;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ProjectContentsRepository contents;
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
    void adminWorkbenchRunsRealRequestsAndTransfersFiles() throws Exception {
        owner = UserId.of(ids.nextId());
        String email = "workbench-" + owner.value() + "@example.test";
        users.save(
                User.restore(
                        owner,
                        email,
                        passwords.encode("workbench-test-password"),
                        "API 관리자",
                        Role.ADMIN,
                        AccountStatus.ACTIVE));
        Path frontend = Path.of("..", "frontend").toAbsolutePath().normalize();
        Path output = Path.of("build", "admin-api-browser.log").toAbsolutePath();
        ProcessBuilder builder =
                new ProcessBuilder(
                        System.getenv("LABFLOW_ADMIN_NODE"), "tests/api-workbench.backend.ts");
        builder.directory(frontend.toFile());
        builder.environment().put("LABFLOW_BACKEND_URL", "http://127.0.0.1:" + port);
        builder.environment().put("LABFLOW_ADMIN_EMAIL", email);
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
        assertThat(finished).as("Workbench browser timeout: %s", report).isTrue();
        assertThat(process.exitValue()).as("Workbench browser: %s", report).isZero();
        assertThat(report).contains("PASS: ADMIN workbench through actual HTTP");
    }
}
