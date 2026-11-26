-- The new Idempotency-Key protocol is independent of legacy X-Request-ID records.
CREATE TABLE idempotency_request (
    id BIGINT NOT NULL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    api_key_id BIGINT NOT NULL,
    scope_hash VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    owner_token VARCHAR(36) NOT NULL,
    state ENUM('PROCESSING','COMPLETED') NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    processing_until DATETIME(6) NOT NULL,
    response_status INT,
    response_headers VARCHAR(4096),
    response_body LONGTEXT,
    body_sha256 VARCHAR(64),
    artifact_id BIGINT,
    CONSTRAINT uk_idempotency_scope UNIQUE (scope_hash),
    CONSTRAINT uk_idempotency_artifact UNIQUE (artifact_id),
    INDEX idx_idempotency_expiry (expires_at),
    INDEX idx_idempotency_project (project_id),
    INDEX idx_idempotency_api_key (api_key_id),
    CONSTRAINT fk_idempotency_project FOREIGN KEY (project_id) REFERENCES project_entity(id),
    CONSTRAINT fk_idempotency_api_key FOREIGN KEY (api_key_id) REFERENCES project_api_key_entity(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Retire the old protocol. Experimental records and uploaded files remain intact.
DROP TABLE IF EXISTS sdk_request_entity;
ALTER TABLE run_artifact_entity DROP CONSTRAINT uk_run_artifact_request;
DROP INDEX IF EXISTS uk_run_artifact_request ON run_artifact_entity;
ALTER TABLE run_artifact_entity DROP COLUMN IF EXISTS client_request_id;
