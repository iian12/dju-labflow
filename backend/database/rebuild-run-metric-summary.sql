-- Administrative recovery only. Stop ALL metric writers and readers first; back up the DB.
-- Use after reverting to a pre-V2 application that wrote history without maintaining summaries.
-- DELETE is intentional: both the clear and backfill roll back together on SQL failure.
START TRANSACTION;
DELETE FROM run_metric_summary;
INSERT INTO run_metric_summary (run_id, metric_key, latest_metric_id, latest_recorded_at, max_step)
SELECT run_id, metric_key, id, recorded_at, max_step
FROM (
    SELECT id, run_id, metric_key, recorded_at,
           MAX(step) OVER (PARTITION BY run_id, metric_key) AS max_step,
           ROW_NUMBER() OVER (PARTITION BY run_id, metric_key ORDER BY recorded_at DESC, id DESC) AS position
    FROM run_metric_entity
) ranked
WHERE position = 1;
COMMIT;
