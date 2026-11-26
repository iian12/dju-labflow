-- Derived read model. Deploy with all metric writers stopped; backfill completes before startup.
CREATE TABLE run_metric_summary (
    run_id BIGINT NOT NULL,
    metric_key VARCHAR(100) NOT NULL,
    latest_metric_id BIGINT NOT NULL,
    latest_recorded_at DATETIME(6) NOT NULL,
    max_step BIGINT,
    PRIMARY KEY (run_id, metric_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO run_metric_summary (run_id, metric_key, latest_metric_id, latest_recorded_at, max_step)
SELECT run_id, metric_key, id, recorded_at, max_step
FROM (
    SELECT id, run_id, metric_key, recorded_at,
           MAX(step) OVER (PARTITION BY run_id, metric_key) AS max_step,
           ROW_NUMBER() OVER (PARTITION BY run_id, metric_key ORDER BY recorded_at DESC, id DESC) AS position
    FROM run_metric_entity
) ranked
WHERE position = 1;
