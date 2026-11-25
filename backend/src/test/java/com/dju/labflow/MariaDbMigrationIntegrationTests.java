package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.auth.application.PersistentRequestLimiter;
import com.dju.labflow.global.schema.SchemaManagementCommand;
import com.dju.labflow.global.schema.SchemaVerifier;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
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
        SchemaVerifier.verify(url, "root", PASSWORD);
        SchemaManagementCommand.execute("baseline", url, "root", PASSWORD);
        assertThatThrownBy(() -> SchemaManagementCommand.execute("baseline", url, "root", PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has Flyway history");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
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
