package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dju.labflow.project.key.application.*;
import com.dju.labflow.project.key.domain.*;
import com.dju.labflow.project.key.domain.repository.ProjectApiKeyRepository;
import com.dju.labflow.project.key.exception.InvalidProjectApiKeyException;
import com.dju.labflow.project.key.util.ProjectApiKeyHasher;
import com.dju.labflow.project.project.domain.*;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** 명시적인 PC의 격리 MariaDB만 사용하며 운영 .env나 원격 DB에 연결하지 않는다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "LABFLOW_AUTH_TEST_DB_URL", matches = ".+")
class MariaDbApiKeyAuthenticationIntegrationTests {
    private ConfigurableApplicationContext context;
    private ProjectApiKeyAuthenticationService authentication;
    private ProjectApiKeyRepository keys;
    private ProjectRepository projects;
    private UserRepository users;
    private ProjectApiKeyAuthenticationAttempt worker;
    private PlatformTransactionManager transactions;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private EntityManagerFactory entityManagers;
    private final AtomicLong ids = new AtomicLong(800000L);
    private Fixture fixture;

    @BeforeAll
    void startIsolatedApplication() {
        String url = System.getenv("LABFLOW_AUTH_TEST_DB_URL");
        URI target = URI.create(url.substring("jdbc:".length()));
        assertThat(target.getHost()).isEqualTo("127.0.0.1");
        assertThat(target.getPort()).isEqualTo(33070);
        assertThat(target.getPath()).isEqualTo("/labflow_auth_test");
        context =
                new SpringApplicationBuilder(BackendApplication.class)
                        .profiles("test")
                        .run(
                                "--server.address=127.0.0.1",
                                "--server.port=0",
                                "--spring.datasource.url=" + url,
                                "--spring.datasource.username=root",
                                "--spring.datasource.password="
                                        + System.getenv("LABFLOW_AUTH_TEST_DB_PASSWORD"),
                                "--spring.datasource.driver-class-name=org.mariadb.jdbc.Driver",
                                "--spring.datasource.hikari.maximum-pool-size=12",
                                "--spring.jpa.hibernate.ddl-auto=validate",
                                "--spring.flyway.enabled=true",
                                "--DOTENV_LOCATION=optional:classpath:/auth-test-no-dotenv.properties");
        authentication = context.getBean(ProjectApiKeyAuthenticationService.class);
        keys = context.getBean(ProjectApiKeyRepository.class);
        projects = context.getBean(ProjectRepository.class);
        users = context.getBean(UserRepository.class);
        worker =
                AopTestUtils.getUltimateTargetObject(
                        context.getBean(ProjectApiKeyAuthenticationAttempt.class));
        transactions = context.getBean(PlatformTransactionManager.class);
        dataSource = context.getBean(DataSource.class);
        entityManagers = context.getBean(EntityManagerFactory.class);
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("11.8.");
        assertThat(jdbc.queryForObject("SELECT @@innodb_snapshot_isolation", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT @@tx_isolation", String.class))
                .isEqualTo("REPEATABLE-READ");
    }

    @BeforeEach
    void createCommittedFixture() {
        long id = ids.incrementAndGet();
        User owner =
                users.save(
                        User.restore(
                                UserId.of(id),
                                "auth" + id + "@example.invalid",
                                "encoded",
                                "auth test",
                                Role.USER,
                                AccountStatus.ACTIVE));
        Project project =
                projects.save(
                        Project.create(
                                ProjectId.of(id),
                                "auth test",
                                null,
                                owner.getId(),
                                ProjectVisibility.PRIVATE,
                                Instant.now()));
        String raw = "isolated-test-key-" + id;
        ProjectApiKey key =
                keys.save(
                        ProjectApiKey.create(
                                ProjectApiKeyId.of(id),
                                project.getId(),
                                owner.getId(),
                                "test",
                                "lfp_test",
                                new ProjectApiKeyHasher().hash(raw),
                                ProjectApiKeyStatus.ACTIVE,
                                Instant.now(),
                                null,
                                null));
        fixture = new Fixture(owner, project, key, raw);
    }

    @AfterEach
    void cleanup() {
        ReflectionTestUtils.setField(worker, "projectApiKeyRepository", keys);
        if (fixture == null) return;
        jdbc.update("DELETE FROM project_api_key_entity WHERE id=?", fixture.key.getId().value());
        jdbc.update("DELETE FROM project_entity WHERE id=?", fixture.project.getId().value());
        jdbc.update("DELETE FROM users WHERE id=?", fixture.owner.getId().value());
    }

    @AfterAll
    void stopApplication() {
        if (context != null) context.close();
    }

    @Test
    void reproducesTheOldRepeatableReadConflictWithSnapshotIsolationEnabled() throws Exception {
        long id = fixture.key.getId().value();
        try (Connection first = dataSource.getConnection();
                Connection second = dataSource.getConnection()) {
            first.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            first.setAutoCommit(false);
            try (PreparedStatement read =
                    first.prepareStatement(
                            "SELECT version FROM project_api_key_entity WHERE id=?")) {
                read.setLong(1, id);
                try (ResultSet result = read.executeQuery()) {
                    assertThat(result.next()).isTrue();
                }
            }
            try (PreparedStatement change =
                    second.prepareStatement(
                            "UPDATE project_api_key_entity SET version=version+1 WHERE id=?")) {
                change.setLong(1, id);
                assertThat(change.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement stale =
                    first.prepareStatement(
                            "UPDATE project_api_key_entity SET version=version+1 WHERE id=?")) {
                stale.setLong(1, id);
                assertThatThrownBy(stale::executeUpdate)
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                failure -> assertThat(failure.getErrorCode()).isEqualTo(1020));
            } finally {
                first.rollback();
            }
        }
    }

    @Test
    void twentyConcurrentClientsAuthenticateWithoutChangingTheDefaultIsolation() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++)
                tasks.add(
                        pool.submit(
                                () -> {
                                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                                    for (int round = 0; round < 15; round++)
                                        assertThat(
                                                        authentication
                                                                .authenticate(fixture.raw)
                                                                .projectId())
                                                .isEqualTo(fixture.project.getId());
                                    return null;
                                }));
            start.countDown();
            for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        assertThat(freshKey().getLastUsedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT @@tx_isolation", String.class))
                .isEqualTo("REPEATABLE-READ");
        assertThat(jdbc.queryForObject("SELECT @@innodb_snapshot_isolation", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rollsBackUsageAndReentersANewReadCommittedTransactionOnRetry() {
        AtomicInteger updates = new AtomicInteger();
        List<Object> transactionHolders = new ArrayList<>();
        ProjectApiKeyRepository wrapped = wrap();
        doAnswer(
                        call -> {
                            assertThat(jdbc.queryForObject("SELECT @@tx_isolation", String.class))
                                    .isEqualTo("READ-COMMITTED");
                            transactionHolders.add(
                                    TransactionSynchronizationManager.getResource(entityManagers));
                            boolean changed =
                                    keys.recordUsage(call.getArgument(0), call.getArgument(1));
                            if (updates.getAndIncrement() == 0) throw conflict();
                            return changed;
                        })
                .when(wrapped)
                .recordUsage(any(), any());
        assertThat(authentication.authenticate(fixture.raw).projectId())
                .isEqualTo(fixture.project.getId());
        assertThat(updates).hasValue(2);
        assertThat(transactionHolders).hasSize(2).doesNotContainNull();
        assertThat(transactionHolders.get(0)).isNotSameAs(transactionHolders.get(1));
        assertThat(freshKey().getVersion()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT @@tx_isolation", String.class))
                .isEqualTo("REPEATABLE-READ");
    }

    @Test
    void retryRechecksOwnerStatusAfterTheFailedTransactionRollsBack() {
        AtomicInteger updates = new AtomicInteger();
        ProjectApiKeyRepository wrapped = wrap();
        doAnswer(
                        call -> {
                            keys.recordUsage(call.getArgument(0), call.getArgument(1));
                            updates.incrementAndGet();
                            TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                        @Override
                                        public void afterCompletion(int status) {
                                            assertThat(status).isEqualTo(STATUS_ROLLED_BACK);
                                            TransactionTemplate next =
                                                    new TransactionTemplate(transactions);
                                            next.setPropagationBehavior(
                                                    TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                                            next.executeWithoutResult(
                                                    ignored ->
                                                            jdbc.update(
                                                                    "UPDATE users SET account_status='BLOCKED' WHERE id=?",
                                                                    fixture.owner.getId().value()));
                                        }
                                    });
                            throw conflict();
                        })
                .when(wrapped)
                .recordUsage(any(), any());
        assertThatThrownBy(() -> authentication.authenticate(fixture.raw))
                .isInstanceOf(InvalidProjectApiKeyException.class);
        assertThat(updates).hasValue(1);
        assertThat(freshKey().getLastUsedAt()).isNull();
        assertThat(freshKey().getVersion()).isZero();
    }

    @Test
    void revocationCommittedBeforeUsageRecordingRejectsAuthentication() throws Exception {
        CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        ProjectApiKeyRepository wrapped = wrap();
        doAnswer(
                        call -> {
                            reached.countDown();
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            return keys.recordUsage(call.getArgument(0), call.getArgument(1));
                        })
                .when(wrapped)
                .recordUsage(any(), any());
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> pending = pool.submit(() -> authentication.authenticate(fixture.raw));
            try {
                assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
                context.getBean(ProjectApiKeyService.class)
                        .revoke(
                                fixture.owner.getId(),
                                fixture.project.getId(),
                                fixture.key.getId());
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InvalidProjectApiKeyException.class);
        }
        assertThat(freshKey().isRevoked()).isTrue();
        assertThat(freshKey().getLastUsedAt()).isNull();
    }

    @Test
    void exhaustedRetriesReturn503BeforeSdkWritesAndRollbackEveryUsageUpdate() throws Exception {
        AtomicInteger updates = new AtomicInteger();
        ProjectApiKeyRepository wrapped = wrap();
        doAnswer(
                        call -> {
                            keys.recordUsage(call.getArgument(0), call.getArgument(1));
                            updates.incrementAndGet();
                            throw conflict();
                        })
                .when(wrapped)
                .recordUsage(any(), any());
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/sdk/runs"))
                        .header("Authorization", "Bearer " + fixture.raw)
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"name\":\"must-not-be-written\"}"))
                        .build();
        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body())
                .contains("AUTHENTICATION_TEMPORARILY_UNAVAILABLE")
                .doesNotContain(fixture.raw, "private SQL");
        assertThat(response.headers().firstValue("Retry-After")).contains("1");
        assertThat(updates).hasValue(3);
        assertThat(freshKey().getLastUsedAt()).isNull();
        assertThat(freshKey().getVersion()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM run_entity WHERE project_id=?",
                                Integer.class,
                                fixture.project.getId().value()))
                .isZero();
    }

    @Test
    void usageTimeRemainsMonotonicAndStaleObjectsCannotOverwriteIt() {
        ProjectApiKey stale = freshKey();
        Instant newer = Instant.parse("2026-11-26T01:00:00Z"), older = newer.minusSeconds(60);
        assertThat(keys.recordUsage(stale.getId(), newer)).isTrue();
        assertThat(keys.recordUsage(stale.getId(), older)).isTrue();
        assertThat(freshKey().getLastUsedAt()).isEqualTo(newer);
        assertThatThrownBy(() -> keys.update(stale))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }

    private ProjectApiKeyRepository wrap() {
        ProjectApiKeyRepository wrapped =
                mock(
                        ProjectApiKeyRepository.class,
                        org.mockito.AdditionalAnswers.delegatesTo(keys));
        ReflectionTestUtils.setField(worker, "projectApiKeyRepository", wrapped);
        return wrapped;
    }

    private RuntimeException conflict() {
        return new RuntimeException(new SQLException("private SQL test conflict", "HY000", 1020));
    }

    private ProjectApiKey freshKey() {
        return keys.findById(fixture.key.getId()).orElseThrow();
    }

    private record Fixture(User owner, Project project, ProjectApiKey key, String raw) {}
}
