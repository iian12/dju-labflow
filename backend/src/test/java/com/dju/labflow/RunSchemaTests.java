package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.run.log.infrastructure.persistence.RunLogEntity;
import com.dju.labflow.run.text.infrastructure.persistence.RunTextEntity;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 배포 DDL의 기본 문법과 키 제약을 격리된 H2 MariaDB 모드에서 검사한다. */
class RunSchemaTests {
    @Test
    void sdkRequestMigrationIsRepeatableAndScopesIdentityToProject() throws Exception {
        try (var connection =
                DriverManager.getConnection("jdbc:h2:mem:sdk-request-ddl;MODE=MariaDB", "sa", "")) {
            var migration = new FileSystemResource("database/sdk-requests.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "insert into sdk_request_entity(id,project_id,request_key,fingerprint,result_run_id,created_at) "
                                + "values(1,1,'key','hash',1,current_timestamp)");
                assertThatThrownBy(
                                () ->
                                        statement.executeUpdate(
                                                "insert into sdk_request_entity(id,project_id,request_key,fingerprint,result_run_id,created_at) "
                                                        + "values(2,1,'key','hash',1,current_timestamp)"))
                        .isInstanceOf(SQLException.class);
                assertThat(
                                statement.executeUpdate(
                                        "insert into sdk_request_entity(id,project_id,request_key,fingerprint,result_run_id,created_at) "
                                                + "values(2,2,'key','hash',2,current_timestamp)"))
                        .isEqualTo(1);
            }
        }
    }

    @Test
    void heartbeatMigrationPreservesExistingRuns() throws Exception {
        try (var connection =
                DriverManager.getConnection("jdbc:h2:mem:heartbeat-ddl;MODE=MariaDB", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("database/runs.sql"));
            try (var statement = connection.createStatement()) {
                statement.execute("alter table run_entity drop column last_heartbeat_at");
                statement.executeUpdate(
                        "insert into run_entity(id,version,project_id,created_by,name,status,started_at) "
                                + "values(1,0,1,1,'existing','RUNNING',current_timestamp)");
            }
            ScriptUtils.executeSqlScript(
                    connection, new FileSystemResource("database/run-heartbeat.sql"));
            try (var statement = connection.createStatement();
                    var result =
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
        var registry =
                new StandardServiceRegistryBuilder()
                        .applySetting("hibernate.dialect", "org.hibernate.dialect.MariaDBDialect")
                        .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                        .build();
        try {
            var metadata =
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
        try (var connection =
                DriverManager.getConnection("jdbc:h2:mem:run-ddl;MODE=MariaDB", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("database/runs.sql"));
            var tables = new HashSet<String>();
            try (var result =
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
            try (var statement = connection.createStatement()) {
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
    void deploymentArtifactDdlEnforcesRequestAndStorageIdentityWithoutUniqueFileNames()
            throws Exception {
        try (var connection =
                DriverManager.getConnection("jdbc:h2:mem:artifact-ddl;MODE=MariaDB", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("database/runs.sql"));
            try (var insert =
                    connection.prepareStatement(
                            "insert into run_artifact_entity(id,version,run_id,name,kind,content_type,"
                                    + "expected_size_bytes,bucket,object_key,status,client_request_id,created_at,upload_expires_at) "
                                    + "values(?,0,?,'model.pt','MODEL','application/octet-stream',10,'files',?,"
                                    + "'UPLOADING',?,current_timestamp,dateadd('HOUR',1,current_timestamp))")) {
                insert.setLong(1, 1L);
                insert.setLong(2, 1L);
                insert.setString(3, "object-1");
                insert.setString(4, "request-1");
                insert.executeUpdate();

                insert.setLong(1, 2L);
                insert.setString(3, "object-2");
                assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class);

                insert.setString(4, "request-2");
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
