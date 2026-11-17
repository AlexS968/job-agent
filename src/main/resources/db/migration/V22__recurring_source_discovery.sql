CREATE TABLE search_monthly_budget (
    period_start DATE PRIMARY KEY,
    reserved_attempts INTEGER NOT NULL CHECK (reserved_attempts BETWEEN 0 AND 120)
);

ALTER TABLE search_pilot_attempts ADD COLUMN budget_period DATE REFERENCES search_monthly_budget(period_start);

ALTER TABLE source_discovery_candidates
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN verification_attempts SMALLINT NOT NULL DEFAULT 0 CHECK (verification_attempts BETWEEN 0 AND 3),
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX source_discovery_pending
    ON source_discovery_candidates (next_attempt_at, id)
    WHERE verification_outcome IN ('UNVERIFIED', 'RETRYABLE_FAILURE');

-- Existing manual verification remains limited to one fetch (at most three requests).
-- Automatic v2 may inspect a homepage and three careers pages for each of three evidence hosts.
ALTER TABLE source_verification_attempts
    DROP CONSTRAINT source_verification_attempts_requests_check,
    ADD CONSTRAINT source_verification_attempts_requests_check CHECK (
        (method_version = 'source-verifier-auto-v2' AND requests BETWEEN 0 AND 45)
        OR (method_version <> 'source-verifier-auto-v2' AND requests BETWEEN 0 AND 3)
    );
