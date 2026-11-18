CREATE TABLE gmail_alert_messages (
    message_id VARCHAR(128) PRIMARY KEY,
    observed_at TIMESTAMPTZ NOT NULL,
    recognized_cards INTEGER NOT NULL CHECK (recognized_cards BETWEEN 0 AND 100),
    unsupported_links INTEGER NOT NULL CHECK (unsupported_links BETWEEN 0 AND 100),
    status VARCHAR(16) NOT NULL CHECK (status IN ('COMPLETE', 'NEEDS_REVIEW')),
    review_reason VARCHAR(40),
    completed_at TIMESTAMPTZ,
    CONSTRAINT gmail_alert_completion CHECK ((status = 'COMPLETE') = (completed_at IS NOT NULL))
);

CREATE TABLE gmail_alert_cards (
    message_id VARCHAR(128) NOT NULL REFERENCES gmail_alert_messages(message_id),
    linkedin_job_id VARCHAR(32) NOT NULL,
    source_item_id BIGINT NOT NULL REFERENCES source_items(id),
    vacancy_id BIGINT NOT NULL REFERENCES vacancies(id),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('CREATED', 'UPDATED', 'UNCHANGED', 'STALE')),
    advertised_direction VARCHAR(20) NOT NULL CHECK (advertised_direction IN
        ('GERMANY', 'AUSTRIA', 'REMOTE_EUROPE', 'REMOTE_WORLDWIDE', 'UNKNOWN')),
    direction_evidence VARCHAR(120),
    PRIMARY KEY (message_id, linkedin_job_id)
);
CREATE INDEX gmail_alert_cards_vacancy ON gmail_alert_cards(vacancy_id);
