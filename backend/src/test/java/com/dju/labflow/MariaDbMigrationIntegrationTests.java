package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.auth.application.PersistentRequestLimiter;
import com.dju.labflow.global.schema.SchemaManagementCommand;
import com.dju.labflow.global.schema.SchemaVerifier;
import com.dju.labflow.project.key.domain.ProjectApiKeyId;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.metric.application.RunMetricCommand;
import com.dju.labflow.run.run.application.RunRecordService;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.user.domain.UserId;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 독립 Windows MariaDB 프로세스만 사용한다. 앱 .env나 기존 DB에 연결하지 않는다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "LABFLOW_MARIADB_BIN", matches = ".+")
class MariaDbMigrationIntegrationTests {
    @TempDir static Path temporary;
    private Process server;
    private Path bin;
    private int port;
    private static final String PASSWORD = "isolated-schema-test-only";

    @BeforeAll
    void startIsolatedServer() throws Exception {
        bin = Path.of(System.getenv("LABFLOW_MARIADB_BIN")).toAbsolutePath();
        assertThat(bin.resolve("mariadb-install-db.exe")).isRegularFile();
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path data = temporary.resolve("data");
        Path initLog = temporary.resolve("init.log");
        Process init =
                new ProcessBuilder(
                                bin.resolve("mariadb-install-db.exe").toString(),
                                "--datadir=" + data,
                                "--port=" + port,
                                "--password=" + PASSWORD)
                        .redirectErrorStream(true)
                        .redirectOutput(initLog.toFile())
                        .start();
        boolean initialized = init.waitFor(40, TimeUnit.SECONDS);
        if (!initialized) {
            init.destroyForcibly();
            init.waitFor(5, TimeUnit.SECONDS);
        }
        assertThat(initialized)
                .as("MariaDB initialization: %s", Files.readString(initLog))
                .isTrue();
        assertThat(init.exitValue())
                .as("MariaDB initialization: %s", Files.readString(initLog))
                .isZero();
        Path log = temporary.resolve("server.log");
        server =
                new ProcessBuilder(
                                bin.resolve("mariadbd.exe").toString(),
                                "--no-defaults",
                                "--basedir=" + bin.getParent(),
                                "--datadir=" + data,
                                "--port=" + port,
                                "--bind-address=127.0.0.1",
                                "--innodb-buffer-pool-size=32M",
                                "--max-connections=40",
                                "--console")
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        for (int attempt = 0; attempt < 100 && server.isAlive(); attempt++) {
            try (Connection ignored = connect(url("mysql"))) {
                return;
            } catch (java.sql.SQLException starting) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("MariaDB startup failed: " + Files.readString(log));
    }

    @AfterAll
    void stopIsolatedServer() throws Exception {
        if (server == null) return;
        try {
            Process shutdown =
                    new ProcessBuilder(
                                    bin.resolve("mariadb-admin.exe").toString(),
                                    "--no-defaults",
                                    "--host=127.0.0.1",
                                    "--port=" + port,
                                    "--user=root",
                                    "--password=" + PASSWORD,
                                    "shutdown")
                            .redirectErrorStream(true)
                            .redirectOutput(temporary.resolve("shutdown.log").toFile())
                            .start();
            if (!shutdown.waitFor(10, TimeUnit.SECONDS)) shutdown.destroyForcibly();
        } finally {
            if (!server.waitFor(10, TimeUnit.SECONDS)) {
                server.destroyForcibly();
                server.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void freshMariaDbMigratesBeforeJpaValidationAndUsesTheSharedAtomicLimiter() throws Exception {
        String url = database();
        try (ConfigurableApplicationContext context = application(url)) {
            Flyway flyway = context.getBean(Flyway.class);
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            SchemaVerifier.verify(url, "root", PASSWORD);
            PersistentRequestLimiter limiter = context.getBean(PersistentRequestLimiter.class);
            AtomicInteger accepted = new AtomicInteger(), rejected = new AtomicInteger();
            try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
                List<Future<?>> requests = new ArrayList<>();
                for (int index = 0; index < 12; index++)
                    requests.add(
                            pool.submit(
                                    () -> {
                                        try {
                                            limiter.check(
                                                    "mariadb",
                                                    "same-client",
                                                    3,
                                                    Duration.ofHours(1));
                                            accepted.incrementAndGet();
                                        } catch (ResponseStatusException limited) {
                                            assertThat(limited.getStatusCode().value())
                                                    .isEqualTo(429);
                                            rejected.incrementAndGet();
                                        }
                                    }));
                for (Future<?> request : requests) request.get(10, TimeUnit.SECONDS);
            }
            assertThat(accepted).hasValue(3);
            assertThat(rejected).hasValue(9);
        }
        assertThat(flyway(url).validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void explicitBaselineAndSubsequentUpgradePreserveLegacyData() throws Exception {
        String url = database();
        try (Connection connection = connect(url)) {
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/migration/V1__initial_schema.sql"));
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "insert into users(id,version,email,encoded_password,name,role,account_status) values(1,0,'legacy@example.test','hash','기존 사용자','USER','ACTIVE')");
                statement.executeUpdate(
                        "insert into run_log_entity(id,run_id,message,level,source,recorded_at) values(1,1,'한글과 이모지 🐈','INFO','STDOUT',current_timestamp(6))");
            }
        }
        Flyway flyway = flyway(url);
        assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty");
        SchemaVerifier.verifyVersionOne(url, "root", PASSWORD);
        SchemaManagementCommand.execute("baseline", url, "root", PASSWORD);
        assertThatThrownBy(() -> SchemaManagementCommand.execute("baseline", url, "root", PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has Flyway history");
        // This test supplies its own V2 below; keep the baseline at V1 here.
        Path migrations = Files.createDirectory(temporary.resolve("upgrade"));
        try (java.io.InputStream source =
                new ClassPathResource("db/migration/V1__initial_schema.sql").getInputStream()) {
            Files.copy(source, migrations.resolve("V1__initial_schema.sql"));
        }
        Files.writeString(
                migrations.resolve("V2__test_add_notes.sql"),
                "ALTER TABLE users ADD COLUMN migration_test_note VARCHAR(40);");
        Flyway upgraded =
                Flyway.configure()
                        .dataSource(url, "root", PASSWORD)
                        .locations("filesystem:" + migrations)
                        .load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        try (Connection connection = connect(url);
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "select name,migration_test_note from users where id=1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("기존 사용자");
            assertThat(result.getString(2)).isNull();
        }
        try (Connection connection = connect(url);
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery("select message from run_log_entity where id=1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("한글과 이모지 🐈");
        }
    }

    @Test
    void baselineVerificationRejectsMissingIndexesAndUniqueConstraints() throws Exception {
        String url = database();
        try (Connection connection = connect(url)) {
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/migration/V1__initial_schema.sql"));
        }
        try (Connection connection = connect(url);
                Statement statement = connection.createStatement()) {
            statement.execute("alter table run_metric_entity drop index idx_run_metric_latest");
            statement.execute(
                    "alter table project_api_key_entity drop index uk_project_api_key_hash");
        }
        assertThatThrownBy(() -> SchemaManagementCommand.execute("baseline", url, "root", PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run_metric_entity requires index")
                .hasMessageContaining("project_api_key_entity requires unique index");
        assertThat(flyway(url).info().current()).isNull();
    }

    @Test
    void baselineVerificationRejectsLegacyColumnDefinitionsWithoutRecordingHistory()
            throws Exception {
        String url = database();
        try (Connection connection = connect(url)) {
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/migration/V1__initial_schema.sql"));
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "alter table project_entity modify visibility enum('PRIVATE','PUBLIC','TEAM') not null");
                statement.execute("alter table users modify version bigint null");
                statement.execute("alter table users modify role enum('admin','user') not null");
                statement.execute(
                        "alter table users modify name varchar(10) character set utf8mb3 not null");
                statement.execute("alter table run_entity modify started_at datetime(0) not null");
            }
        }
        assertThatThrownBy(() -> SchemaManagementCommand.execute("baseline", url, "root", PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different enum domain")
                .hasMessageContaining("users.role has a different enum domain")
                .hasMessageContaining("users.version must be NOT NULL")
                .hasMessageContaining("users.name is too short")
                .hasMessageContaining("users.name must use utf8mb4")
                .hasMessageContaining("requires microsecond precision");
        assertThat(flyway(url).info().current()).isNull();
    }

    @Test
    void jpaValidationRefusesSchemaDriftWithoutRepairingTheDatabase() throws Exception {
        String url = database();
        flyway(url).migrate();
        try (Connection connection = connect(url);
                Statement statement = connection.createStatement()) {
            statement.execute("alter table run_entity drop column last_heartbeat_at");
        }
        assertThatThrownBy(
                        () -> {
                            try (ConfigurableApplicationContext ignored = application(url)) {
                                fail("Schema drift must prevent startup");
                            }
                        })
                .hasStackTraceContaining("last_heartbeat_at");
        try (Connection connection = connect(url);
                ResultSet result =
                        connection
                                .getMetaData()
                                .getColumns(
                                        connection.getCatalog(),
                                        null,
                                        "run_entity",
                                        "last_heartbeat_at")) {
            assertThat(result.next()).isFalse();
        }
    }

    @Test
    void summaryBackfillAndConcurrentWritesMatchHistoryAndUseBoundedReads() throws Exception {
        String url = database().replace("socketTimeout=5000", "socketTimeout=60000");
        Flyway.configure()
                .dataSource(url, "root", PASSWORD)
                .locations("classpath:db/migration")
                .target("1")
                .load()
                .migrate();
        try (Connection connection = connect(url)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "insert into project_entity(id,version,name,created_by,visibility,created_at) values(1,0,'summary-test',1,'PUBLIC',current_timestamp(6))");
                for (int run = 100; run < 120; run++)
                    statement.executeUpdate(
                            "insert into run_entity(id,version,project_id,created_by,name,status,started_at) values("
                                    + run
                                    + ",0,1,1,'test','RUNNING',current_timestamp(6))");
            }
            try (PreparedStatement insert =
                    connection.prepareStatement(
                            "insert into run_metric_entity(id,run_id,metric_key,metric_value,step,recorded_at) values(?,?,?,?,?,?)")) {
                for (int row = 0; row < 100_000; row++) {
                    insert.setLong(1, row + 1L);
                    insert.setLong(2, 100L + row % 20);
                    insert.setString(3, "metric_" + row / 20 % 5);
                    insert.setDouble(4, row / 100_000.0);
                    insert.setLong(5, row / 20);
                    insert.setTimestamp(
                            6,
                            Timestamp.from(
                                    Instant.parse("2026-11-25T00:00:00Z").plusSeconds(row % 500)));
                    insert.addBatch();
                    if ((row + 1) % 1000 == 0) insert.executeBatch();
                }
            }
            connection.commit();
        }
        assertThat(flyway(url).migrate().migrationsExecuted).isEqualTo(2);
        String original =
                "select id from (select id,run_id,metric_key,row_number() over (partition by run_id,metric_key order by recorded_at desc,id desc) rn from run_metric_entity where run_id between 100 and 119) ranked where rn=1 order by run_id,metric_key";
        String projected =
                "select m.id from run_metric_summary s join run_metric_entity m on m.id=s.latest_metric_id where s.run_id between 100 and 119 order by s.run_id,s.metric_key";
        assertThat(queryIds(url, projected)).hasSize(100).isEqualTo(queryIds(url, original));
        List<Double> oldTimes = new ArrayList<>(), newTimes = new ArrayList<>();
        try (Connection connection = connect(url)) {
            for (int sample = 0; sample < 35; sample++) {
                double oldMillis = measureQuery(connection, original),
                        newMillis = measureQuery(connection, projected);
                if (sample >= 5) {
                    oldTimes.add(oldMillis);
                    newTimes.add(newMillis);
                }
            }
        }
        oldTimes.sort(Double::compareTo);
        newTimes.sort(Double::compareTo);
        System.out.printf(
                java.util.Locale.ROOT,
                "SUMMARY_QUERY_BENCHMARK rows=100000 runs=20 keys=5 samples=30 oldP50Ms=%.3f oldP95Ms=%.3f newP50Ms=%.3f newP95Ms=%.3f%n",
                oldTimes.get(14),
                oldTimes.get(28),
                newTimes.get(14),
                newTimes.get(28));
        // A pre-V2 rollback can append history without updating the read model.
        try (Connection connection = connect(url);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "insert into run_metric_entity(id,run_id,metric_key,metric_value,step,recorded_at) values(100001,100,'metric_0',0.4,1,'2026-11-26 00:00:00')");
            assertThat(queryIds(url, projected)).isNotEqualTo(queryIds(url, original));
            ScriptUtils.executeSqlScript(
                    connection,
                    new org.springframework.core.io.FileSystemResource(
                            "database/rebuild-run-metric-summary.sql"));
        }
        assertThat(queryIds(url, projected)).isEqualTo(queryIds(url, original));
        try (ConfigurableApplicationContext context = application(url)) {
            RunRecordService records = context.getBean(RunRecordService.class);
            ProjectApiKeyPrincipal principal =
                    new ProjectApiKeyPrincipal(
                            ProjectApiKeyId.of(1L), ProjectId.of(1L), UserId.of(1L));
            RunId run = RunId.of(100L);
            try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
                List<Future<?>> writes = new ArrayList<>();
                for (int index = 0; index < 32; index++) {
                    int value = index;
                    writes.add(
                            pool.submit(
                                    () ->
                                            records.metrics(
                                                    principal,
                                                    run,
                                                    new RunMetricCommand(
                                                            null,
                                                            9000L + value,
                                                            null,
                                                            Map.of("metric_0", (double) value),
                                                            Instant.parse(
                                                                            "2026-11-26T00:00:00.123456789Z")
                                                                    .plusSeconds(value % 3)))));
                }
                for (Future<?> write : writes) write.get(20, TimeUnit.SECONDS);
            }
            records.metrics(
                    principal,
                    run,
                    new RunMetricCommand(
                            null,
                            12000L,
                            null,
                            Map.of("metric_0", 0.1),
                            Instant.parse("2025-01-01T00:00:00Z")));
            records.metrics(
                    principal,
                    run,
                    new RunMetricCommand(
                            null,
                            null,
                            null,
                            Map.of("metric_0", 0.2),
                            Instant.parse("2027-01-01T00:00:00Z")));
            assertThat(queryIds(url, projected)).isEqualTo(queryIds(url, original));
            try (Connection connection = connect(url);
                    Statement statement = connection.createStatement();
                    ResultSet result =
                            statement.executeQuery(
                                    "select max(max_step) from run_metric_summary where run_id=100")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(12000);
            }
            List<Long> beforeRollback = queryIds(url, projected);
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                    .executeWithoutResult(
                            status -> {
                                records.metrics(
                                        principal,
                                        run,
                                        new RunMetricCommand(
                                                null,
                                                20000L,
                                                null,
                                                Map.of("metric_0", 0.0),
                                                Instant.parse("2028-01-01T00:00:00Z")));
                                status.setRollbackOnly();
                            });
            assertThat(queryIds(url, projected))
                    .isEqualTo(beforeRollback)
                    .isEqualTo(queryIds(url, original));
            // DB write failure after the history INSERT must roll both tables back on MariaDB too.
            try (Connection connection = connect(url);
                    Statement statement = connection.createStatement()) {
                statement.execute(
                        "alter table run_metric_summary add constraint test_projection_failure check (max_step <> 30000)");
            }
            assertThatThrownBy(
                            () ->
                                    records.metrics(
                                            principal,
                                            run,
                                            new RunMetricCommand(
                                                    null,
                                                    30000L,
                                                    null,
                                                    Map.of("metric_0", 0.0),
                                                    Instant.parse("2029-01-01T00:00:00Z"))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(queryIds(url, projected))
                    .isEqualTo(beforeRollback)
                    .isEqualTo(queryIds(url, original));
        }
    }

    private List<Long> queryIds(String url, String sql) throws Exception {
        try (Connection connection = connect(url);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            List<Long> ids = new ArrayList<>();
            while (rows.next()) ids.add(rows.getLong(1));
            return ids;
        }
    }

    private double measureQuery(Connection connection, String sql) throws Exception {
        long start = System.nanoTime();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) rows.getLong(1);
        }
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    @Test
    void idempotencyLedgerUsesNowaitRecoveryAndKeepsArtifactDataDuringUpgrade() throws Exception {
        String url = database();
        Flyway.configure()
                .dataSource(url, "root", PASSWORD)
                .locations("classpath:db/migration")
                .target("2")
                .load()
                .migrate();
        try (Connection connection = connect(url);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "insert into project_entity(id,version,name,created_by,visibility,created_at) values(1,0,'ledger-test',1,'PUBLIC',current_timestamp(6))");
            statement.executeUpdate(
                    "insert into project_api_key_entity(id,version,project_id,created_by,name,key_prefix,key_hash,status,created_at) values(1,0,1,1,'key','prefix','hash','ACTIVE',current_timestamp(6))");
            statement.executeUpdate(
                    "insert into sdk_request_entity(id,project_id,request_key,fingerprint,result_run_id,created_at) values(1,1,'old-key','old-payload',1,current_timestamp(6))");
            statement.executeUpdate(
                    "insert into run_artifact_entity(id,version,run_id,name,kind,content_type,expected_size_bytes,size_bytes,bucket,object_key,status,client_request_id,created_at,upload_expires_at,uploaded_at) values(1,0,1,'preserved.bin','OTHER','application/octet-stream',10,10,'files','preserved-object','READY','old-artifact-key',current_timestamp(6),current_timestamp(6),current_timestamp(6))");
        }
        assertThat(flyway(url).migrate().migrationsExecuted).isEqualTo(1);
        try (Connection connection = connect(url);
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "select name,size_bytes,object_key from run_artifact_entity where id=1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("preserved.bin");
            assertThat(result.getLong(2)).isEqualTo(10);
            assertThat(result.getString(3)).isEqualTo("preserved-object");
            try (ResultSet columns =
                    connection
                            .getMetaData()
                            .getColumns(
                                    connection.getCatalog(),
                                    null,
                                    "run_artifact_entity",
                                    "client_request_id")) {
                assertThat(columns.next()).isFalse();
            }
            try (ResultSet tables =
                    connection
                            .getMetaData()
                            .getTables(connection.getCatalog(), null, "sdk_request_entity", null)) {
                assertThat(tables.next()).isFalse();
            }
        }
        try (ConfigurableApplicationContext context = application(url)) {
            com.dju.labflow.sdk.idempotency.IdempotencyStore ledger =
                    context.getBean(com.dju.labflow.sdk.idempotency.IdempotencyStore.class);
            PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
            ProjectApiKeyPrincipal principal =
                    new ProjectApiKeyPrincipal(
                            ProjectApiKeyId.of(1L), ProjectId.of(1L), UserId.of(1L));
            String scope = "a".repeat(64), fingerprint = "b".repeat(64);
            com.dju.labflow.sdk.idempotency.IdempotencyStore.Claim claim =
                    ledger.claim(principal, scope, fingerprint);
            try (Connection connection = connect(url);
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "update idempotency_request set processing_until=utc_timestamp(6)-interval 1 second where scope_hash='"
                                + scope
                                + "'");
            }
            CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<?> owner =
                        executor.submit(
                                () ->
                                        new TransactionTemplate(manager)
                                                .executeWithoutResult(
                                                        status -> {
                                                            ledger.lock(claim);
                                                            locked.countDown();
                                                            try {
                                                                if (!release.await(
                                                                        10, TimeUnit.SECONDS))
                                                                    throw new AssertionError(
                                                                            "Owner not released");
                                                            } catch (
                                                                    InterruptedException
                                                                            interrupted) {
                                                                Thread.currentThread().interrupt();
                                                                throw new IllegalStateException(
                                                                        interrupted);
                                                            }
                                                            status.setRollbackOnly();
                                                        }));
                try {
                    assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                    long started = System.nanoTime();
                    assertThatThrownBy(() -> ledger.claim(principal, scope, fingerprint))
                            .isInstanceOf(
                                    com.dju.labflow.sdk.idempotency.IdempotencyException.class)
                            .satisfies(
                                    error ->
                                            assertThat(
                                                            ((com.dju.labflow.sdk.idempotency
                                                                                    .IdempotencyException)
                                                                            error)
                                                                    .status())
                                                    .isEqualTo(409));
                    assertThat(Duration.ofNanos(System.nanoTime() - started))
                            .isLessThan(Duration.ofSeconds(2));
                } finally {
                    release.countDown();
                }
                owner.get(5, TimeUnit.SECONDS);
            }
            com.dju.labflow.sdk.idempotency.IdempotencyStore.Claim recovered =
                    ledger.claim(principal, scope, fingerprint);
            assertThat(recovered.ownerToken()).isEqualTo(claim.ownerToken());
            assertThat(recovered.replay()).isFalse();
            new TransactionTemplate(manager)
                    .executeWithoutResult(
                            status -> {
                                ledger.lock(recovered);
                                ledger.complete(
                                        recovered,
                                        new com.dju.labflow.sdk.idempotency.IdempotencyStore
                                                .Response(
                                                201,
                                                "{\"Content-Type\":\"application/json\"}",
                                                "{\"runId\":123}",
                                                null));
                            });
            com.dju.labflow.sdk.idempotency.IdempotencyStore.Claim replay =
                    ledger.claim(principal, scope, fingerprint);
            assertThat(replay.replay()).isTrue();
            assertThat(replay.response().body()).isEqualTo("{\"runId\":123}");
            assertThatThrownBy(() -> ledger.claim(principal, scope, "c".repeat(64)))
                    .isInstanceOf(com.dju.labflow.sdk.idempotency.IdempotencyException.class)
                    .satisfies(
                            error ->
                                    assertThat(
                                                    ((com.dju.labflow.sdk.idempotency
                                                                            .IdempotencyException)
                                                                    error)
                                                            .status())
                                            .isEqualTo(422));
            SchemaVerifier.verify(url, "root", PASSWORD);
        }
    }

    private ConfigurableApplicationContext application(String url) {
        return new SpringApplicationBuilder(BackendApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + url,
                        "--spring.datasource.driver-class-name=org.mariadb.jdbc.Driver",
                        "--spring.datasource.username=root",
                        "--spring.datasource.password=" + PASSWORD,
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.flyway.enabled=true",
                        "--DOTENV_LOCATION=optional:classpath:/schema-test-no-dotenv.properties",
                        "--labflow.artifacts.directory=" + temporary.resolve("artifacts"));
    }

    private String database() throws Exception {
        String name = "schema_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connect(url("mysql"));
                Statement statement = connection.createStatement()) {
            statement.execute("create database " + name + " character set utf8mb4");
        }
        return url(name);
    }

    private String url(String database) {
        return "jdbc:mariadb://127.0.0.1:"
                + port
                + "/"
                + database
                + "?connectTimeout=500&socketTimeout=5000";
    }

    private Connection connect(String url) throws Exception {
        return DriverManager.getConnection(url, "root", PASSWORD);
    }

    private Flyway flyway(String url) {
        return Flyway.configure()
                .dataSource(url, "root", PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .baselineVersion("1")
                .cleanDisabled(true)
                .load();
    }
}
