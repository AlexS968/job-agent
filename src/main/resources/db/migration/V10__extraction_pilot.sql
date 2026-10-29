CREATE TABLE extraction_pilot_budget (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    reserved_micro_usd BIGINT NOT NULL DEFAULT 0 CHECK (reserved_micro_usd >= 0),
    observed_micro_usd BIGINT NOT NULL DEFAULT 0 CHECK (observed_micro_usd >= 0),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 80),
    primary_distinct INTEGER NOT NULL DEFAULT 0 CHECK (primary_distinct BETWEEN 0 AND 30),
    comparison_distinct INTEGER NOT NULL DEFAULT 0 CHECK (comparison_distinct BETWEEN 0 AND 10),
    active_work_id BIGINT,
    active_claim_token UUID,
    active_since TIMESTAMPTZ,
    halted_reason VARCHAR(40),
    CONSTRAINT extraction_active_pair CHECK ((active_work_id IS NULL AND active_claim_token IS NULL
        AND active_since IS NULL) OR (active_work_id IS NOT NULL AND active_claim_token IS NOT NULL
        AND active_since IS NOT NULL))
);
INSERT INTO extraction_pilot_budget(id) VALUES (1);

CREATE TABLE extraction_work (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vacancy_id BIGINT NOT NULL REFERENCES vacancies(id),
    source_item_id BIGINT NOT NULL REFERENCES source_items(id),
    vacancy_revision CHAR(64) NOT NULL,
    provider VARCHAR(24) NOT NULL CHECK (provider = 'ANTHROPIC'),
    model VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    schema_version VARCHAR(40) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    input_json JSONB NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('PRIMARY', 'COMPARISON')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('CLAIMED', 'SUCCEEDED', 'FAILED', 'UNCERTAIN')),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    result_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT extraction_work_identity UNIQUE
        (vacancy_id, source_item_id, vacancy_revision, provider, model, prompt_version, schema_version, policy_version)
);
CREATE INDEX extraction_work_status_idx ON extraction_work(status, lease_until);

CREATE TABLE extraction_attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    work_id BIGINT NOT NULL REFERENCES extraction_work(id),
    claim_token UUID NOT NULL UNIQUE,
    reserved_micro_usd BIGINT NOT NULL CHECK (reserved_micro_usd > 0),
    observed_cost_micro_usd BIGINT CHECK (observed_cost_micro_usd >= 0),
    input_tokens INTEGER CHECK (input_tokens >= 0),
    cache_creation_input_tokens INTEGER CHECK (cache_creation_input_tokens >= 0),
    cache_read_input_tokens INTEGER CHECK (cache_read_input_tokens >= 0),
    output_tokens INTEGER CHECK (output_tokens >= 0),
    outcome VARCHAR(24) NOT NULL,
    provider_request_id VARCHAR(128),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);
