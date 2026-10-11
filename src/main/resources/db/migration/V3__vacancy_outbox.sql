CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_id BIGINT NOT NULL REFERENCES vacancies(id),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    run_id BIGINT NOT NULL REFERENCES agent_runs(id),
    payload JSONB NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    total_attempts INTEGER NOT NULL DEFAULT 0,
    cycle_attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_by VARCHAR(120),
    claim_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    failure_code VARCHAR(40),
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT outbox_state_check CHECK (state IN ('PENDING', 'CLAIMED', 'COMPLETED', 'FAILED')),
    CONSTRAINT outbox_attempts_check CHECK (total_attempts >= 0 AND cycle_attempts >= 0),
    CONSTRAINT outbox_claim_check CHECK ((state = 'CLAIMED') = (claim_token IS NOT NULL AND claimed_by IS NOT NULL AND lease_until IS NOT NULL))
);

CREATE INDEX outbox_pending_due_idx ON outbox_events(next_attempt_at, occurred_at)
    WHERE state = 'PENDING';
CREATE INDEX outbox_expired_lease_idx ON outbox_events(lease_until)
    WHERE state = 'CLAIMED';

CREATE TABLE consumer_deliveries (
    consumer_id VARCHAR(120) NOT NULL,
    event_id UUID NOT NULL REFERENCES outbox_events(event_id),
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (consumer_id, event_id)
);

CREATE TABLE synthetic_vacancy_observations (
    vacancy_id BIGINT PRIMARY KEY REFERENCES vacancies(id),
    first_event_id UUID NOT NULL UNIQUE REFERENCES outbox_events(event_id),
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
