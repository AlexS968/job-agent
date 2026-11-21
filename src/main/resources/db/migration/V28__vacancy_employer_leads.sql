-- A posting is the provenance of a lead. A conflict-free verified registry identity links employers across channels.
CREATE TABLE vacancy_employer_leads (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vacancy_id bigint NOT NULL REFERENCES vacancies(id),
    source_item_id bigint NOT NULL REFERENCES source_items(id),
    advertised_name varchar(255) NOT NULL,
    posting_url text NOT NULL,
    site_hint text,
    apply_hint text,
    status varchar(24) NOT NULL CHECK (status IN
        ('PENDING','SKIPPED','WAITING_FOR_SEARCH','RETRYABLE_FAILURE','MANUAL_REVIEW','VERIFIED')),
    reason varchar(64),
    needs_search boolean NOT NULL DEFAULT false,
    attempts smallint NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    due_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token uuid,
    lease_until timestamptz,
    registry_id bigint REFERENCES verified_source_registry(id),
    official_page_url text,
    UNIQUE (vacancy_id, advertised_name),
    CHECK ((claim_token IS NULL) = (lease_until IS NULL)),
    CHECK ((status = 'VERIFIED') = (registry_id IS NOT NULL))
);
CREATE INDEX vacancy_employer_leads_due ON vacancy_employer_leads (due_at, id)
    WHERE status IN ('PENDING','WAITING_FOR_SEARCH','RETRYABLE_FAILURE');

-- Only automatically activated boards enter renewal. Preserve manual enablement and disablement.
ALTER TABLE verified_source_registry ADD COLUMN renew_after timestamptz;
UPDATE verified_source_registry s SET renew_after = verified_at + interval '21 days'
WHERE active AND status = 'VERIFIED' AND 'automated-official-page' = (
    SELECT a.origin FROM source_verification_attempts a
    WHERE a.platform=s.platform AND a.region=s.region AND a.source_id=s.source_id AND a.outcome='VERIFIED'
    ORDER BY a.id LIMIT 1
);
CREATE INDEX verified_sources_due_renewal ON verified_source_registry (renew_after, id)
    WHERE active AND status='VERIFIED' AND renew_after IS NOT NULL;
