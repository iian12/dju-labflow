-- Apply once before deploying shared authentication request limits to an existing MariaDB.
CREATE TABLE IF NOT EXISTS auth_request_bucket (
    bucket_key VARCHAR(64) NOT NULL PRIMARY KEY,
    expires_at TIMESTAMP(6) NOT NULL,
    request_count BIGINT NOT NULL,
    INDEX idx_auth_bucket_expiry (expires_at)
);
