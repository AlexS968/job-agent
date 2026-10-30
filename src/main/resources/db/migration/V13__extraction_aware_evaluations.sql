ALTER TABLE vacancy_evaluations ADD COLUMN extraction_work_id BIGINT REFERENCES extraction_work(id);
ALTER TABLE vacancy_evaluations ADD COLUMN extraction_basis VARCHAR(24) NOT NULL DEFAULT 'NONE'
    CHECK (extraction_basis IN ('NONE', 'SUCCEEDED', 'PARTIAL'));
ALTER TABLE vacancy_evaluations ADD COLUMN extraction_details JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE vacancy_evaluations DROP CONSTRAINT vacancy_evaluation_identity;
ALTER TABLE vacancy_evaluations ADD CONSTRAINT vacancy_evaluation_identity UNIQUE NULLS NOT DISTINCT
    (vacancy_id, vacancy_revision, profile_id, profile_version, criteria_hash,
     policy_version, extraction_basis, extraction_work_id);
