CREATE TABLE active_source_import_cursor (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    last_source_id BIGINT NOT NULL DEFAULT 0 CHECK (last_source_id >= 0)
);
INSERT INTO active_source_import_cursor (singleton, last_source_id) VALUES (TRUE, 0);

CREATE TABLE active_source_import_claims (
    source_id BIGINT PRIMARY KEY REFERENCES verified_source_registry(id),
    claim_token UUID NOT NULL,
    claimed_until TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE source_discovery_candidates (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    candidate_url TEXT NOT NULL UNIQUE,
    first_search_attempt_id BIGINT NOT NULL REFERENCES search_pilot_attempts(id),
    last_search_attempt_id BIGINT NOT NULL REFERENCES search_pilot_attempts(id),
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verification_outcome VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
    verification_reason VARCHAR(64),
    official_domain VARCHAR(253),
    official_page_url TEXT,
    platform VARCHAR(32),
    region VARCHAR(16),
    source_id VARCHAR(64)
);

CREATE TABLE vacancy_candidate_assessments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vacancy_id BIGINT NOT NULL REFERENCES vacancies(id),
    source_item_id BIGINT NOT NULL REFERENCES source_items(id),
    policy_version VARCHAR(40) NOT NULL,
    decision VARCHAR(24) NOT NULL CHECK (decision IN ('CANDIDATE', 'NOT_CANDIDATE', 'REVIEW')),
    reason VARCHAR(48) NOT NULL,
    assessed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT vacancy_candidate_assessment_identity UNIQUE (source_item_id, policy_version)
);
CREATE INDEX vacancy_candidate_assessments_vacancy ON vacancy_candidate_assessments (vacancy_id, policy_version);
