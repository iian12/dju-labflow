package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.run.log.infrastructure.persistence.RunLogEntity;
import com.dju.labflow.run.text.infrastructure.persistence.RunTextEntity;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 배포 DDL의 기본 문법과 키 제약을 격리된 H2 MariaDB 모드에서 검사한다. */
class RunSchemaTests {
    @Test
    void currentSchemaUsesOnlyTheResponseLedgerForIdempotency() throws Exception {
        String url = "jdbc:h2:mem:idempotency-ddl;MODE=MariaDB;DB_CLOSE_DELAY=-1";
        org.flywaydb.core.Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            try (ResultSet columns =
                    connection
                            .getMetaData()
                            .getColumns(
                                    null, "PUBLIC", "RUN_ARTIFACT_ENTITY", "CLIENT_REQUEST_ID")) {
                assertThat(columns.next()).isFalse();
            }
            try (ResultSet tables =
                    connection
                            .getMetaData()
                            .getTables(null, "PUBLIC", "SDK_REQUEST_ENTITY", null)) {
                assertThat(tables.next()).isFalse();
            }
            statement.executeUpdate(
                    "insert into project_entity(id,version,name,created_by,visibility,created_at) values(1,0,'p',1,'PUBLIC',current_timestamp)");
            statement.executeUpdate(
                    "insert into project_api_key_entity(id,version,project_id,created_by,name,key_prefix,key_hash,status,created_at) values(1,0,1,1,'key','prefix','hash','ACTIVE',current_timestamp)");
            String insert =
                    "insert into idempotency_request(id,project_id,api_key_id,scope_hash,fingerprint,owner_token,state,created_at,expires_at,processing_until) values(%d,1,1,'%s','fingerprint','owner','PROCESSING',current_timestamp,dateadd('DAY',15,current_timestamp),dateadd('MINUTE',2,current_timestamp))";
            statement.executeUpdate(insert.formatted(1, "scope"));
            assertThatThrownBy(() -> statement.executeUpdate(insert.formatted(2, "scope")))
                    .isInstanceOf(SQLException.class);
            assertThat(statement.executeUpdate(insert.formatted(3, "different-scope")))
                    .isEqualTo(1);
        }
    }

    @Test
    void heartbeatMigrationPreservesExistingRuns() throws Exception {
        try (Connection connection =
                DriverManager.getConnection("jdbc:h2:mem:heartbeat-ddl;MODE=MariaDB", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("database/runs.sql"));
            try (Statement statement = connection.createStatement()) {
                statement.execute("alter table run_entity drop column last_heartbeat_at");
                statement.executeUpdate(
                        "insert into run_entity(id,version,project_id,created_by,name,status,started_at) "
                                + "values(1,0,1,1,'existing','RUNNING',current_timestamp)");
            }
            ScriptUtils.executeSqlScript(
                    connection, new FileSystemResource("database/run-heartbeat.sql"));
            try (Statement statement = connection.createStatement();
                    ResultSet result =
                            statement.executeQuery(
                                    "select name,last_heartbeat_at from run_entity where id=1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("name")).isEqualTo("existing");
                assertThat(result.getTimestamp("last_heartbeat_at")).isNull();
            }
        }
    }

    @Test
    void mariaDbMappingDoesNotShrinkTextAndLogColumnsToTinytext() {
        StandardServiceRegistry registry =
                new StandardServiceRegistryBuilder()
                        .applySetting("hibernate.dialect", "org.hibernate.dialect.MariaDBDialect")
                        .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                        .build();
        try {
            Metadata metadata =
                    new MetadataSources(registry)
                            .addAnnotatedClass(RunTextEntity.class)
                            .addAnnotatedClass(RunLogEntity.class)
                            .buildMetadata();
            assertThat(
                            metadata.getEntityBinding(RunTextEntity.class.getName())
                                    .getProperty("text")
                                    .getColumns()
                                    .getFirst()
                                    .getSqlType(metadata))
                    .isEqualToIgnoringCase("longtext");
            assertThat(
                            metadata.getEntityBinding(RunLogEntity.class.getName())
                                    .getProperty("message")
                                    .getColumns()
                                    .getFirst()
                                    .getSqlType(metadata))
                    .isEqualToIgnoringCase("longtext");
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    void deploymentDdlCreatesAllTablesAndEnforcesParameterIdentity() throws Exception {
        try (Connection connection =
                DriverManager.getConnection("jdbc:h2:mem:run-ddl;MODE=MariaDB", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("database/runs.sql"));
            HashSet<String> tables = new HashSet<>();
            try (ResultSet result =
                    connection
                            .getMetaData()
                            .getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
                while (result.next()) tables.add(result.getString("TABLE_NAME"));
            }
            assertThat(tables)
                    .contains(
                            "RUN_ENTITY",
                            "RUN_METADATA",
                            "RUN_PARAMETER_ENTITY",
                            "RUN_METRIC_ENTITY",
                            "RUN_LOG_ENTITY",
                            "RUN_TEXT_ENTITY",
                            "RUN_ARTIFACT_ENTITY",
                            "RUN_SYSTEM_METRIC_ENTITY",
                            "RUN_SYSTEM_METRIC_GPU");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "insert into run_parameter_entity(id,run_id,parameter_key,parameter_value,created_at) values(1,1,'optimizer','adam',current_timestamp)");
                assertThatThrownBy(
                                () ->
                                        statement.executeUpdate(
                                                "insert into run_parameter_entity(id,run_id,parameter_key,parameter_value,created_at) values(2,1,'optimizer','sgd',current_timestamp)"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    void deploymentArtifactDdlEnforcesOnlyStorageIdentityWithoutRequestKeys() throws Exception {
        try (Connection connection =
                DriverManager.getConnection("jdbc:h2:mem:artifact-ddl;MODE=MariaDB", "sa", "")) {
            org.flywaydb.core.Flyway.configure()
                    .dataSource("jdbc:h2:mem:artifact-ddl;MODE=MariaDB", "sa", "")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            try (PreparedStatement insert =
                    connection.prepareStatement(
                            "insert into run_artifact_entity(id,version,run_id,name,kind,content_type,"
                                    + "expected_size_bytes,bucket,object_key,status,created_at,upload_expires_at) "
                                    + "values(?,0,?,'model.pt','MODEL','application/octet-stream',10,'files',?,"
                                    + "'UPLOADING',current_timestamp,dateadd('HOUR',1,current_timestamp))")) {
                insert.setLong(1, 1L);
                insert.setLong(2, 1L);
                insert.setString(3, "object-1");
                insert.executeUpdate();

                insert.setLong(1, 2L);
                insert.setString(3, "object-2");
                assertThat(insert.executeUpdate()).isEqualTo(1);

                insert.setLong(1, 3L);
                insert.setLong(2, 2L);
                assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class);

                insert.setString(3, "object-3");
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
        }
    }
}
