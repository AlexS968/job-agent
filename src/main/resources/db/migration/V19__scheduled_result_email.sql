CREATE TABLE scheduled_result_reports (
    schedule_date DATE PRIMARY KEY,
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('PREPARED', 'PUBLISHING', 'PUBLISHED', 'UNCERTAIN')),
    de_profile_version VARCHAR(40) NOT NULL,
    de_criteria_hash CHAR(64) NOT NULL,
    at_profile_version VARCHAR(40) NOT NULL,
    at_criteria_hash CHAR(64) NOT NULL,
    subject TEXT NOT NULL,
    report_text TEXT NOT NULL,
    sns_message_id VARCHAR(128),
    prepared_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT scheduled_result_report_size CHECK (octet_length(report_text) <= 4000),
    CONSTRAINT scheduled_result_subject_size CHECK (octet_length(subject) <= 160)
);

CREATE UNIQUE INDEX scheduled_result_one_unresolved ON scheduled_result_reports ((1))
    WHERE status IN ('PREPARED', 'PUBLISHING', 'UNCERTAIN');

CREATE TABLE scheduled_result_items (
    schedule_date DATE NOT NULL REFERENCES scheduled_result_reports(schedule_date),
    profile_id VARCHAR(80) NOT NULL CHECK (profile_id IN ('alex-de', 'alex-at')),
    vacancy_id BIGINT NOT NULL REFERENCES vacancies(id),
    vacancy_revision CHAR(64) NOT NULL,
    profile_version VARCHAR(40) NOT NULL,
    criteria_hash CHAR(64) NOT NULL,
    evaluation_policy VARCHAR(40) NOT NULL,
    country_policy VARCHAR(40) NOT NULL,
    disposition VARCHAR(16) NOT NULL CHECK (disposition IN ('NEW', 'RELEVANT', 'BASELINE')),
    PRIMARY KEY (schedule_date, profile_id, vacancy_id)
);

CREATE INDEX scheduled_result_items_identity ON scheduled_result_items (profile_id, vacancy_id);
