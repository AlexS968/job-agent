ALTER TABLE extraction_attempts
    ADD COLUMN validation_claim_index INTEGER,
    ADD COLUMN validation_category VARCHAR(64),
    ADD COLUMN rejected_output TEXT,
    ADD CONSTRAINT extraction_validation_diagnostic_pair CHECK (
        (validation_claim_index IS NULL AND validation_category IS NULL)
        OR (validation_claim_index >= -1 AND validation_category IS NOT NULL)),
    ADD CONSTRAINT extraction_rejected_output_bound CHECK (
        rejected_output IS NULL OR (validation_category IS NOT NULL
        AND octet_length(rejected_output) <= 16384));
