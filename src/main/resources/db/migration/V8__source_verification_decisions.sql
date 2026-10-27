CREATE TABLE source_verification_decisions (
    employer_domain VARCHAR(253) PRIMARY KEY,
    observed_at TIMESTAMPTZ NOT NULL,
    decision VARCHAR(32) NOT NULL CHECK (decision IN ('VERIFIED', 'UNVERIFIED', 'CONFLICT')),
    evidence_hash CHAR(64) NOT NULL,
    associated_platform VARCHAR(32),
    associated_region VARCHAR(16),
    associated_source_id VARCHAR(64),
    CHECK ((associated_platform IS NULL AND associated_region IS NULL AND associated_source_id IS NULL)
        OR (associated_platform IS NOT NULL AND associated_region IS NOT NULL AND associated_source_id IS NOT NULL))
);

WITH observations AS (
    SELECT LOWER(claimed_employer_domain) AS employer_domain, observed_at,
           CASE WHEN outcome = 'VERIFIED' THEN 'VERIFIED'
                WHEN outcome = 'UNSUPPORTED' OR reason = 'NO_SUPPORTED_LINK' THEN 'UNVERIFIED'
                ELSE 'CONFLICT' END AS decision,
           evidence_hash, platform, region, source_id, id AS sequence
    FROM source_verification_attempts
    WHERE claimed_employer_domain IS NOT NULL AND requests > 0
      AND origin IN ('owner-confirmed', 'independently-reviewed')
      AND outcome IN ('VERIFIED', 'UNSUPPORTED', 'MANUAL_REVIEW')
    UNION ALL
    SELECT LOWER(employer_domain), verified_at,
           CASE WHEN status = 'MANUAL_REVIEW' THEN 'CONFLICT'
                WHEN status = 'UNVERIFIED' THEN 'UNVERIFIED'
                ELSE 'VERIFIED' END,
           evidence_hash, platform, region, source_id, -1
    FROM verified_source_registry
), latest AS (
    SELECT DISTINCT ON (employer_domain) employer_domain, observed_at, decision, evidence_hash
    FROM observations
    ORDER BY employer_domain, observed_at DESC,
             CASE decision WHEN 'CONFLICT' THEN 2 WHEN 'UNVERIFIED' THEN 1 ELSE 0 END DESC,
             sequence DESC
), association AS (
    SELECT DISTINCT ON (employer_domain) employer_domain, platform, region, source_id
    FROM observations
    WHERE decision = 'VERIFIED' AND platform IS NOT NULL
    ORDER BY employer_domain, observed_at DESC, sequence DESC
)
INSERT INTO source_verification_decisions
    (employer_domain, observed_at, decision, evidence_hash,
     associated_platform, associated_region, associated_source_id)
SELECT latest.employer_domain, latest.observed_at, latest.decision, latest.evidence_hash,
       association.platform, association.region, association.source_id
FROM latest LEFT JOIN association USING (employer_domain);

UPDATE verified_source_registry AS source
SET status = CASE WHEN decision.decision = 'CONFLICT' THEN 'MANUAL_REVIEW' ELSE 'UNVERIFIED' END,
    active = FALSE
FROM source_verification_decisions AS decision
WHERE LOWER(source.employer_domain) = decision.employer_domain
  AND decision.decision <> 'VERIFIED' AND source.verified_at <= decision.observed_at;
