-- 기존 MariaDB에 배포 전 적용하는 신규 테이블. 자동 실행되지 않는다.
CREATE TABLE email_verifications (
    user_id BIGINT NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6) NOT NULL,
    window_started_at DATETIME(6) NOT NULL,
    send_count INT NOT NULL,
    failures INT NOT NULL,
    used BOOLEAN NOT NULL,
    PRIMARY KEY (user_id)
);
