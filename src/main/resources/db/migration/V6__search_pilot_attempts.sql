CREATE TABLE search_pilot_budget (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    reserved_attempts INTEGER NOT NULL DEFAULT 0 CHECK (reserved_attempts BETWEEN 0 AND 100)
);
INSERT INTO search_pilot_budget (id) VALUES (1);

CREATE TABLE search_pilot_attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    query_version VARCHAR(32) NOT NULL,
    query_number SMALLINT NOT NULL,
    reserved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    outcome VARCHAR(32) NOT NULL DEFAULT 'RESERVED',
    provider_request_id VARCHAR(128),
    provider_credits INTEGER CHECK (provider_credits >= 0)
);
