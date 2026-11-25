-- Apply once to existing MariaDB databases before enabling metric-sorted run lists.
CREATE INDEX idx_run_metric_latest ON run_metric_entity (run_id, metric_key, recorded_at, id);
