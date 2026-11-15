CREATE TABLE review_feedback (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    vacancy_id BIGINT NOT NULL REFERENCES vacancies(id),
    evaluation_id BIGINT NOT NULL REFERENCES vacancy_evaluations(id),
    profile_id VARCHAR(80) NOT NULL CHECK (profile_id IN ('alex-de', 'alex-at')),
    profile_version VARCHAR(40) NOT NULL,
    vacancy_revision CHAR(64) NOT NULL,
    owner_decision VARCHAR(24) NOT NULL CHECK (owner_decision IN
        ('INTERESTED', 'NOT_INTERESTED', 'NEED_MORE_INFO')),
    owner_reason VARCHAR(40),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT review_feedback_reason CHECK (
        (owner_decision = 'INTERESTED' AND owner_reason IS NULL)
        OR (owner_decision = 'NOT_INTERESTED' AND owner_reason IN
            ('ROLE_MISMATCH', 'SENIORITY', 'LOCATION_OR_WORK_COUNTRY', 'REMOTE_POLICY',
             'SALARY', 'TECH_STACK', 'COMPANY_OR_BUSINESS_MODEL', 'OTHER'))
        OR (owner_decision = 'NEED_MORE_INFO' AND owner_reason IN
            ('WORK_COUNTRY_UNCONFIRMED', 'SALARY_UNKNOWN', 'REMOTE_POLICY_UNKNOWN',
             'ROLE_DETAILS_UNKNOWN', 'OTHER'))
    )
);

CREATE INDEX review_feedback_latest ON review_feedback (profile_id, vacancy_id, id DESC);
