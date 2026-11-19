-- 기존 MariaDB에 배포 전에 한 번 적용한다. 기존 실행/기록은 보존한다.
ALTER TABLE run_entity ADD COLUMN last_heartbeat_at DATETIME(6) NULL;
