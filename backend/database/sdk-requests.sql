-- Legacy pre-Flyway repair reference only. New installs use db/migration/V1__initial_schema.sql.
-- After Flyway adoption, add a new versioned migration instead. See database/README.md.
-- 기존 MariaDB에 배포 전에 적용한다. 프로젝트 삭제 전까지 성공한 SDK 요청을 보존한다.
CREATE TABLE IF NOT EXISTS sdk_request_entity (
    id BIGINT NOT NULL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    result_run_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_sdk_request_project_id UNIQUE (project_id, request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
