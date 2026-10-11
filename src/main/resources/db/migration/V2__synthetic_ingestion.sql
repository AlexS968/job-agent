ALTER TABLE agent_runs
    ADD COLUMN finished_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN result VARCHAR(16),
    ADD COLUMN error_code VARCHAR(32),
    ADD CONSTRAINT agent_runs_result_check CHECK (result IS NULL OR result IN ('INGESTED', 'REPLAYED'));

CREATE TABLE source_items (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_namespace VARCHAR(120) NOT NULL,
    source_item_id VARCHAR(200) NOT NULL,
    listing_namespace VARCHAR(120) NOT NULL,
    listing_id VARCHAR(200) NOT NULL,
    original_url TEXT NOT NULL,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    raw_payload JSONB NOT NULL,
    input_version VARCHAR(40) NOT NULL,
    normalization_version VARCHAR(40) NOT NULL,
    input_digest CHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT source_items_identity UNIQUE (source_namespace, source_item_id)
);

CREATE TABLE employers (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_namespace VARCHAR(120) NOT NULL,
    external_id VARCHAR(200) NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT employers_identity UNIQUE (source_namespace, external_id)
);

CREATE TABLE vacancies (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employer_id BIGINT NOT NULL REFERENCES employers(id),
    source_namespace VARCHAR(120) NOT NULL,
    listing_id VARCHAR(200) NOT NULL,
    title VARCHAR(255) NOT NULL,
    location VARCHAR(255) NOT NULL,
    CONSTRAINT vacancies_identity UNIQUE (source_namespace, listing_id)
);

CREATE TABLE vacancy_sources (
    vacancy_id BIGINT NOT NULL REFERENCES vacancies(id),
    source_item_id BIGINT NOT NULL UNIQUE REFERENCES source_items(id),
    PRIMARY KEY (vacancy_id, source_item_id)
);
