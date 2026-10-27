CREATE TABLE evaluation_profiles (
    profile_id VARCHAR(80) NOT NULL,
    profile_version VARCHAR(40) NOT NULL,
    criteria_hash CHAR(64) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (profile_id, profile_version)
);

CREATE TABLE vacancy_evaluations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vacancy_id BIGINT NOT NULL REFERENCES vacancies(id),
    source_item_id BIGINT NOT NULL REFERENCES source_items(id),
    vacancy_revision CHAR(64) NOT NULL,
    profile_id VARCHAR(80) NOT NULL,
    profile_version VARCHAR(40) NOT NULL,
    criteria_hash CHAR(64) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('SUITABLE', 'NEEDS_REVIEW', 'EXCLUDED')),
    preference_rank INTEGER NOT NULL CHECK (preference_rank BETWEEN 1 AND 9),
    role_classification VARCHAR(16) NOT NULL
        CHECK (role_classification IN ('PREFERRED', 'RESERVE', 'UNMATCHED')),
    reasons JSONB NOT NULL,
    unknowns JSONB NOT NULL,
    evidence JSONB NOT NULL,
    original_url TEXT NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT vacancy_evaluation_identity UNIQUE
        (vacancy_id, vacancy_revision, profile_id, profile_version, criteria_hash, policy_version),
    CONSTRAINT vacancy_evaluation_profile FOREIGN KEY (profile_id, profile_version)
        REFERENCES evaluation_profiles(profile_id, profile_version)
);

CREATE INDEX vacancy_evaluations_profile_idx ON vacancy_evaluations
    (profile_id, profile_version, criteria_hash, policy_version, decision, preference_rank, vacancy_id);
