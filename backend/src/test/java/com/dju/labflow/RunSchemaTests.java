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
}
