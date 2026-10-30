ALTER TABLE extraction_work DROP CONSTRAINT extraction_work_status_check;
ALTER TABLE extraction_work ADD CONSTRAINT extraction_work_status_check
    CHECK (status IN ('CLAIMED', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'UNCERTAIN'));

ALTER TABLE extraction_work
    ADD COLUMN accepted_count INTEGER NOT NULL DEFAULT 0 CHECK (accepted_count >= 0),
    ADD COLUMN rejected_count INTEGER NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),
    ADD COLUMN rejection_diagnostics JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(rejection_diagnostics) = 'array'
            AND jsonb_array_length(rejection_diagnostics) < 24);

ALTER TABLE extraction_work
    ADD CONSTRAINT extraction_partial_has_both_kinds CHECK
        (status <> 'PARTIAL' OR (accepted_count > 0 AND rejected_count > 0)),
    ADD CONSTRAINT extraction_rejection_count_matches CHECK
        (jsonb_array_length(rejection_diagnostics) = rejected_count);
