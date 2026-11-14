ALTER TABLE vacancy_evaluations
    ADD COLUMN country_evidence_policy VARCHAR(40) NOT NULL DEFAULT 'country-v1',
    ADD COLUMN country_eligibility VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (country_eligibility IN ('CONFIRMED', 'UNKNOWN')),
    ADD COLUMN country_evidence VARCHAR(320);

-- Historical evaluations have no country-specific source interpretation. A new
-- evaluation sweep refreshes this projection without rewriting their decisions.
