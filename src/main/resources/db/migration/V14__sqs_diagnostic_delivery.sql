ALTER TABLE outbox_events DROP CONSTRAINT outbox_state_check;
ALTER TABLE outbox_events ADD CONSTRAINT outbox_state_check
    CHECK (state IN ('PENDING', 'CLAIMED', 'COMPLETED', 'PUBLISHED', 'FAILED'));
ALTER TABLE outbox_events ADD COLUMN published_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE outbox_events ADD CONSTRAINT outbox_published_check
    CHECK (state <> 'PUBLISHED' OR published_at IS NOT NULL);

CREATE TABLE sqs_vacancy_observations (
    event_id UUID PRIMARY KEY REFERENCES outbox_events(event_id),
    vacancy_id BIGINT NOT NULL REFERENCES vacancies(id),
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
