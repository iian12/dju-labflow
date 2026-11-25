package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 버전 이력, 기존 DB의 명시적 편입 및 데이터 보존을 검사한다. 실제 MariaDB 검사는 별도다. */
class SchemaMigrationTests {
    @TempDir Path temporary;

    @Test
    void emptyDatabaseMigratesTheCompleteSchemaOnceAndPreservesExistingRows() throws Exception {
        String url = database();
        Flyway flyway = flyway(url, "classpath:db/migration");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            HashSet<String> tables = new HashSet<>();
            try (ResultSet result =
                    connection
                            .getMetaData()
                            .getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
                while (result.next()) tables.add(result.getString("TABLE_NAME"));
            }
            assertThat(tables)
                    .contains(
                            "USERS",
                            "PROJECT_ENTITY",
                            "PROJECT_API_KEY_ENTITY",
                            "REFRESH_TOKENS",
                            "EMAIL_VERIFICATIONS",
                            "AUTH_REQUEST_BUCKET",
                            "RUN_ENTITY",
                            "RUN_METADATA",
                            "RUN_PARAMETER_ENTITY",
                            "RUN_METRIC_ENTITY",
                            "RUN_LOG_ENTITY",
                            "RUN_TEXT_ENTITY",
                            "RUN_SYSTEM_METRIC_ENTITY",
                            "RUN_SYSTEM_METRIC_GPU",
                            "RUN_ARTIFACT_ENTITY",
                            "SDK_REQUEST_ENTITY");
            insertUser(connection);
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertUserPreserved(url);
        assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class);
    }

    @Test
    void unmanagedDatabaseIsRejectedUntilItIsExplicitlyBaselined() throws Exception {
        String url = database();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/migration/V1__initial_schema.sql"));
            insertUser(connection);
        }
        Flyway flyway = flyway(url, "classpath:db/migration");
        assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty");
        assertThat(flyway.info().current()).isNull();
        flyway.baseline();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertUserPreserved(url);
    }

    @Test
    void laterMigrationUpgradesVersionOneWithoutRecreatingItsData() throws Exception {
        String url = database();
        Path migrations = copyInitialMigration();
        Flyway flyway = flyway(url, "filesystem:" + migrations);
        flyway.migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            insertUser(connection);
        }
        Files.writeString(
                migrations.resolve("V2__test_add_notes.sql"),
                "ALTER TABLE users ADD COLUMN migration_test_note VARCHAR(40);");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertUserPreserved(url);
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "select migration_test_note from users where id=1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isNull();
        }
    }

    @Test
    void editingAnAppliedMigrationFailsChecksumValidation() throws Exception {
        String url = database();
        Path migrations = copyInitialMigration();
        Flyway flyway = flyway(url, "filesystem:" + migrations);
        flyway.migrate();
        Path initial = migrations.resolve("V1__initial_schema.sql");
        Files.writeString(
                initial,
                Files.readString(initial)
                        + "\nALTER TABLE users ADD COLUMN changed_history INT;\n");
        assertThat(flyway.validateWithResult().validationSuccessful).isFalse();
        assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("checksum");
    }

    private Path copyInitialMigration() throws Exception {
        Path migrations = Files.createDirectory(temporary.resolve("migrations"));
        try (java.io.InputStream source =
                new ClassPathResource("db/migration/V1__initial_schema.sql").getInputStream()) {
            Files.copy(source, migrations.resolve("V1__initial_schema.sql"));
        }
        return migrations;
    }

    private String database() {
        return "jdbc:h2:mem:schema_" + UUID.randomUUID() + ";MODE=MariaDB;DB_CLOSE_DELAY=-1";
    }

    private Flyway flyway(String url, String location) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(location)
                .baselineOnMigrate(false)
                .baselineVersion("1")
                .cleanDisabled(true)
                .load();
    }

    private void insertUser(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "insert into users(id,version,email,encoded_password,name,role,account_status) values(1,0,'schema@example.test','hash','preserved','USER','ACTIVE')");
        }
    }

    private void assertUserPreserved(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select name from users where id=1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("preserved");
        }
    }
}
