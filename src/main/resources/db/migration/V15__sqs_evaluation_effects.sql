CREATE TABLE sqs_evaluation_effects (
    consumer_id VARCHAR(120) NOT NULL,
    event_id UUID NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('EVALUATED', 'STALE')),
    evaluation_id BIGINT REFERENCES vacancy_evaluations(id),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_id, event_id),
    FOREIGN KEY (consumer_id, event_id) REFERENCES consumer_deliveries(consumer_id, event_id),
    CHECK ((outcome = 'EVALUATED' AND evaluation_id IS NOT NULL)
        OR (outcome = 'STALE' AND evaluation_id IS NULL))
);
