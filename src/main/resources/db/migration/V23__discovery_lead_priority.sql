ALTER TABLE source_discovery_candidates
    ADD COLUMN lead_kind VARCHAR(16) NOT NULL DEFAULT 'EMPLOYER'
        CHECK (lead_kind IN ('ATS', 'EMPLOYER', 'AGGREGATOR', 'NON_EMPLOYER', 'UNSAFE')),
    ADD COLUMN lead_priority SMALLINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT source_discovery_lead_priority_matches_kind CHECK (
        (lead_kind = 'ATS' AND lead_priority = 0)
        OR (lead_kind = 'EMPLOYER' AND lead_priority = 1)
        OR (lead_kind = 'AGGREGATOR' AND lead_priority = 2)
        OR (lead_kind = 'NON_EMPLOYER' AND lead_priority = 2)
        OR (lead_kind = 'UNSAFE' AND lead_priority = 3));

-- One-time classification of retained leads. New leads use DiscoveryLeadPolicy before insertion.
-- An ATS host without a board ID is not an employer career page or a claimable board.
UPDATE source_discovery_candidates SET lead_kind = 'NON_EMPLOYER', lead_priority = 2
WHERE lower(split_part(split_part(candidate_url, '/', 3), ':', 1)) IN
      ('boards.greenhouse.io', 'job-boards.greenhouse.io', 'jobs.lever.co', 'jobs.eu.lever.co');

UPDATE source_discovery_candidates SET lead_kind = 'ATS', lead_priority = 0
WHERE candidate_url ~* '^https://(boards[.]greenhouse[.]io|job-boards[.]greenhouse[.]io|jobs[.]lever[.]co|jobs[.]eu[.]lever[.]co)(:443)?/'
  AND substring(candidate_url FROM '^[A-Za-z]+://[^/]+/([^/?#]+)') ~ '^[a-z0-9][a-z0-9-]{0,63}$'
  AND substring(candidate_url FROM '^[A-Za-z]+://[^/]+/([^/?#]+)') <> 'embed';

UPDATE source_discovery_candidates SET lead_kind = 'AGGREGATOR', lead_priority = 2
WHERE lower(split_part(candidate_url, '/', 3)) ~
      '(^|[.])(linkedin[.]com|indeed[.]com|glassdoor[.]com|wellfound[.]com|remotefront[.]com|devjobs[.]de|devjobs[.]at)$';

UPDATE source_discovery_candidates SET lead_kind = 'NON_EMPLOYER', lead_priority = 2
WHERE lower(split_part(candidate_url, '/', 3)) ~
      '(^|[.])(greenhouse[.]com|tavily[.]com)$';

UPDATE source_discovery_candidates SET lead_kind = 'UNSAFE', lead_priority = 3
WHERE candidate_url NOT LIKE 'https://%';

UPDATE source_discovery_candidates
SET verification_outcome = 'MANUAL_REVIEW',
    verification_reason = CASE WHEN lead_kind = 'AGGREGATOR' THEN 'AGGREGATOR_ONLY_LEAD'
                               WHEN lead_kind = 'NON_EMPLOYER' THEN 'NON_EMPLOYER_SITE'
                               ELSE 'UNSAFE_URL' END,
    claim_token = NULL, claimed_until = NULL
WHERE lead_kind IN ('AGGREGATOR', 'NON_EMPLOYER', 'UNSAFE')
  AND verification_outcome IN ('UNVERIFIED', 'RETRYABLE_FAILURE')
  AND (claimed_until IS NULL OR claimed_until <= CURRENT_TIMESTAMP);

DROP INDEX source_discovery_pending;
CREATE INDEX source_discovery_pending
    ON source_discovery_candidates (lead_priority, next_attempt_at, id)
    WHERE verification_outcome IN ('UNVERIFIED', 'RETRYABLE_FAILURE');
