package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 배포 DDL의 기본 문법과 키 제약을 격리된 H2 MariaDB 모드에서 검사한다. */
class RunSchemaTests {
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
