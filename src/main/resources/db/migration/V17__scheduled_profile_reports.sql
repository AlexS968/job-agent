CREATE TABLE scheduled_batch_invocations (
    schedule_date DATE PRIMARY KEY,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETE', 'FAILED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);

INSERT INTO scheduled_batch_invocations (schedule_date, status, started_at, finished_at)
SELECT schedule_date,
       CASE WHEN status = 'PUBLISHED' THEN 'COMPLETE'
            WHEN status IN ('FAILED', 'UNCERTAIN') THEN 'FAILED' ELSE 'RUNNING' END,
       started_at, finished_at
FROM scheduled_batch_reports;

DROP INDEX scheduled_batch_one_active;
ALTER TABLE scheduled_batch_reports DROP CONSTRAINT scheduled_batch_reports_schedule_date_key;
ALTER TABLE scheduled_batch_reports
    ADD CONSTRAINT scheduled_batch_report_profile_slot UNIQUE (schedule_date, profile_id);
ALTER TABLE scheduled_batch_reports ADD COLUMN criteria_hash VARCHAR(64);
ALTER TABLE scheduled_batch_reports ADD CONSTRAINT scheduled_report_criteria_hash_format
    CHECK (criteria_hash IS NULL OR criteria_hash ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX scheduled_batch_one_active_invocation ON scheduled_batch_invocations ((1))
    WHERE status = 'RUNNING';
