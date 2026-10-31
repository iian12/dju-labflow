-- Run MVP: 기존 사용자/프로젝트 스키마가 준비된 MariaDB에서 적용한다.
-- 자동 실행되지 않는다. Hibernate validate를 사용하기 전에 적용한다.
CREATE TABLE IF NOT EXISTS run_entity (
    id BIGINT NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    status ENUM('RUNNING','COMPLETED','FAILED') NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6),
    error VARCHAR(4000),
    INDEX idx_run_project_time (project_id, started_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS run_metadata (
    run_id BIGINT NOT NULL,
    metadata_key VARCHAR(100) NOT NULL,
    metadata_value VARCHAR(2000) NOT NULL,
    PRIMARY KEY (run_id, metadata_key),
    CONSTRAINT fk_run_metadata_run FOREIGN KEY (run_id) REFERENCES run_entity(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS run_parameter_entity (
    id BIGINT NOT NULL PRIMARY KEY,
    run_id BIGINT NOT NULL,
    parameter_key VARCHAR(100) NOT NULL,
    parameter_value VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_run_parameter_time (run_id),
    CONSTRAINT uk_run_parameter_key UNIQUE (run_id, parameter_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS run_metric_entity (
    id BIGINT NOT NULL PRIMARY KEY,
    run_id BIGINT NOT NULL,
    metric_key VARCHAR(100) NOT NULL,
    metric_value DOUBLE NOT NULL,
    epoch INT,
    step BIGINT,
    elapsed_time DOUBLE,
    recorded_at DATETIME(6) NOT NULL,
    INDEX idx_run_metric_time (run_id, recorded_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS run_log_entity (
    id BIGINT NOT NULL PRIMARY KEY,
    run_id BIGINT NOT NULL,
    message LONGTEXT NOT NULL,
    level ENUM('DEBUG','INFO','WARN','ERROR') NOT NULL,
    source ENUM('USER','STDOUT','STDERR','SDK') NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    INDEX idx_run_log_time (run_id, recorded_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS run_text_entity (
    id BIGINT NOT NULL PRIMARY KEY,
    run_id BIGINT NOT NULL,
    text_key VARCHAR(100) NOT NULL,
    text LONGTEXT NOT NULL,
    step BIGINT,
    recorded_at DATETIME(6) NOT NULL,
    INDEX idx_run_text_time (run_id, recorded_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS run_system_metric_entity (
    id BIGINT NOT NULL PRIMARY KEY,
    run_id BIGINT NOT NULL,
    cpu_utilization DOUBLE,
    ram_used BIGINT,
    ram_total BIGINT,
    elapsed_time DOUBLE,
    recorded_at DATETIME(6) NOT NULL,
    INDEX idx_run_systemmetric_time (run_id, recorded_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS run_system_metric_gpu (
    system_metric_id BIGINT NOT NULL,
    gpu_index INT NOT NULL,
    utilization DOUBLE,
    memory_used BIGINT,
    memory_total BIGINT,
    CONSTRAINT uk_system_metric_gpu UNIQUE (system_metric_id, gpu_index),
    CONSTRAINT fk_gpu_system_metric FOREIGN KEY (system_metric_id) REFERENCES run_system_metric_entity(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
