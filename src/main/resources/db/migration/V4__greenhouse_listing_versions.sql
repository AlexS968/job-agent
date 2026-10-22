ALTER TABLE source_items
    ADD COLUMN source_updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN content_digest CHAR(64);

ALTER TABLE vacancies
    ALTER COLUMN location DROP NOT NULL,
    ADD COLUMN description TEXT,
    ADD COLUMN source_updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN content_digest CHAR(64);

ALTER TABLE agent_runs DROP CONSTRAINT agent_runs_result_check;
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_result_check
    CHECK (result IS NULL OR result IN ('INGESTED', 'REPLAYED', 'CREATED', 'UPDATED', 'UNCHANGED', 'STALE'));
