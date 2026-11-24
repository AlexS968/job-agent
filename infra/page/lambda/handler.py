"""Read-only, owner-gated vacancy projection for API Gateway HTTP API."""

import json
import os
from pathlib import Path
from urllib.parse import urlsplit
import uuid

import boto3

MAX_PAGE = 20
MAX_OFFSET = 1000
MAX_BODY = 64_000
MIN_COMPARABLE_FEEDBACK = 10

LINKEDIN_SQL = """
SELECT v.id,
       CASE WHEN card.parsed_direction IS NULL THEN v.title
            ELSE coalesce(card.advertised_title, prior.title, v.title) END,
       em.name, si.listing_id, e.decision,
       e.role_classification, e.reasons::text, e.unknowns::text,
       coalesce(card.parsed_direction, card.advertised_direction),
       CASE WHEN card.parsed_direction IS NULL THEN card.direction_evidence ELSE card.advertised_location END,
       card.advertised_employer
FROM vacancies v
JOIN employers em ON em.id = v.employer_id
JOIN LATERAL (
  SELECT si.id AS source_item_id, si.listing_id
  FROM vacancy_sources vs JOIN source_items si ON si.id = vs.source_item_id
  WHERE vs.vacancy_id = v.id AND si.source_namespace = 'linkedin:gmail'
    AND si.input_version = 'linkedin-alert-v1'
  ORDER BY si.fetched_at DESC, si.id DESC LIMIT 1
) si ON TRUE
JOIN gmail_alert_cards card ON card.source_item_id = si.source_item_id
LEFT JOIN LATERAL (
  SELECT prior_card.advertised_title AS title
  FROM vacancy_sources vs
  JOIN source_items observation ON observation.id = vs.source_item_id
  JOIN gmail_alert_cards prior_card ON prior_card.source_item_id = observation.id
  WHERE vs.vacancy_id = v.id AND prior_card.advertised_title IS NOT NULL
  ORDER BY observation.fetched_at DESC, observation.id DESC LIMIT 1
) prior ON card.parsed_direction IS NOT NULL AND card.advertised_title IS NULL
LEFT JOIN LATERAL (
  SELECT decision, role_classification, reasons, unknowns
  FROM vacancy_evaluations e
  WHERE e.vacancy_id = v.id AND e.vacancy_revision = v.content_digest
    AND e.profile_id = :profile AND e.policy_version = 'rules-v2'
    AND e.profile_version = (
      SELECT profile_version FROM evaluation_profiles
      WHERE profile_id = :profile ORDER BY first_seen_at DESC, profile_version DESC LIMIT 1)
  ORDER BY e.evaluated_at DESC, e.id DESC LIMIT 1
) e ON TRUE
WHERE v.source_namespace = 'linkedin' AND si.listing_id ~ '^[0-9]{4,32}$'
ORDER BY v.id DESC LIMIT :limit OFFSET :offset
"""

def current_shortlist_ctes(profile_filter, for_source_report=False):
    # Shared between the vacancy page and the source report. The filter is a static SQL fragment.
    prefix = "," if for_source_report else "WITH"
    scope = "JOIN membership m ON m.vacancy_id = vs.vacancy_id" if for_source_report else ""
    return f"""
{prefix} current_profile AS (
  SELECT profile_id, profile_version, criteria_hash,
         row_number() OVER (PARTITION BY profile_id ORDER BY first_seen_at DESC,
                            profile_version DESC) AS profile_rank
  FROM evaluation_profiles WHERE {profile_filter}
), current_source AS (
  SELECT vs.vacancy_id, si.id AS source_item_id,
         row_number() OVER (PARTITION BY vs.vacancy_id ORDER BY si.id DESC) AS source_rank
  FROM vacancy_sources vs JOIN source_items si ON si.id = vs.source_item_id
  JOIN vacancies v ON v.id = vs.vacancy_id
  {scope}
  WHERE si.content_digest IS NOT DISTINCT FROM v.content_digest
), current_evaluation AS (
  SELECT e.id, e.profile_id, e.vacancy_id, e.profile_version, e.vacancy_revision,
         e.original_url, e.decision, e.role_classification, e.reasons,
         e.unknowns, e.preference_rank, e.evaluated_at,
         v.title, em.name AS employer,
         row_number() OVER (PARTITION BY e.profile_id, e.vacancy_id ORDER BY e.evaluated_at DESC,
                           e.id DESC) AS evaluation_rank
  FROM vacancy_evaluations e
  JOIN current_profile cp ON cp.profile_id = e.profile_id AND cp.profile_version = e.profile_version
      AND cp.criteria_hash = e.criteria_hash AND cp.profile_rank = 1
  JOIN current_source cs ON cs.vacancy_id = e.vacancy_id
      AND cs.source_item_id = e.source_item_id AND cs.source_rank = 1
  JOIN vacancies v ON v.id = e.vacancy_id
  JOIN employers em ON em.id = v.employer_id
  JOIN source_items si ON si.id = e.source_item_id
  WHERE e.policy_version = 'rules-v2'
    AND e.country_evidence_policy = 'country-v1'
    AND e.country_eligibility = 'CONFIRMED'
    AND e.decision IN ('SUITABLE', 'NEEDS_REVIEW')
    AND e.role_classification IN ('PREFERRED', 'RESERVE')
    AND ((v.source_namespace LIKE 'greenhouse:%' AND si.input_version = 'greenhouse-board-v1')
      OR (v.source_namespace LIKE 'lever:%' AND si.input_version = 'lever-postings-v1'))
)
"""


SQL = current_shortlist_ctes("profile_id = :profile") + """
SELECT ce.id, ce.vacancy_id, ce.title, ce.employer, ce.original_url, ce.decision,
       ce.role_classification, ce.reasons, ce.unknowns, ce.profile_version, ce.vacancy_revision,
       f.id, f.owner_decision, f.owner_reason, f.evaluation_id
FROM current_evaluation ce
LEFT JOIN LATERAL (
  SELECT id, owner_decision, owner_reason, evaluation_id FROM review_feedback
  WHERE vacancy_id = ce.vacancy_id AND profile_id = :profile ORDER BY id DESC LIMIT 1
) f ON TRUE
WHERE ce.evaluation_rank = 1
ORDER BY CASE ce.decision WHEN 'SUITABLE' THEN 0 ELSE 1 END, ce.preference_rank,
         CASE ce.role_classification WHEN 'PREFERRED' THEN 0 ELSE 1 END, ce.vacancy_id
LIMIT :limit OFFSET :offset
"""

FEEDBACK_CTE = """
WITH current_profile AS (
  SELECT profile_id, profile_version, criteria_hash,
         row_number() OVER (PARTITION BY profile_id ORDER BY first_seen_at DESC,
                            profile_version DESC) AS profile_rank
  FROM evaluation_profiles WHERE profile_id = :profile
), current_source AS (
  SELECT vs.vacancy_id, si.id AS source_item_id,
         row_number() OVER (PARTITION BY vs.vacancy_id ORDER BY si.id DESC) AS source_rank
  FROM vacancy_sources vs JOIN source_items si ON si.id = vs.source_item_id
  JOIN vacancies v ON v.id = vs.vacancy_id
  WHERE si.content_digest IS NOT DISTINCT FROM v.content_digest
), current_evaluation AS (
  SELECT e.id, e.vacancy_id, e.profile_version, e.vacancy_revision,
         row_number() OVER (PARTITION BY e.vacancy_id ORDER BY e.evaluated_at DESC,
                           e.id DESC) AS evaluation_rank
  FROM vacancy_evaluations e
  JOIN current_profile cp ON cp.profile_id = e.profile_id AND cp.profile_version = e.profile_version
      AND cp.criteria_hash = e.criteria_hash AND cp.profile_rank = 1
  JOIN current_source cs ON cs.vacancy_id = e.vacancy_id
      AND cs.source_item_id = e.source_item_id AND cs.source_rank = 1
  JOIN vacancies v ON v.id = e.vacancy_id
  JOIN source_items si ON si.id = e.source_item_id
  WHERE e.policy_version = 'rules-v2'
    AND e.country_evidence_policy = 'country-v1'
    AND e.country_eligibility = 'CONFIRMED'
    AND e.decision IN ('SUITABLE', 'NEEDS_REVIEW')
    AND e.role_classification IN ('PREFERRED', 'RESERVE')
    AND ((v.source_namespace LIKE 'greenhouse:%' AND si.input_version = 'greenhouse-board-v1')
      OR (v.source_namespace LIKE 'lever:%' AND si.input_version = 'lever-postings-v1'))
)
"""

INSERT_FEEDBACK_SQL = FEEDBACK_CTE + """
INSERT INTO review_feedback(request_id, vacancy_id, evaluation_id, profile_id,
                            profile_version, vacancy_revision, owner_decision, owner_reason)
SELECT CAST(:request AS uuid), ce.vacancy_id, ce.id, :profile,
       ce.profile_version, ce.vacancy_revision, :decision, :reason
FROM current_evaluation ce
WHERE ce.evaluation_rank = 1 AND ce.id = :evaluation AND ce.vacancy_id = :vacancy
  AND ce.profile_version = :version AND ce.vacancy_revision = :revision
ON CONFLICT (request_id) DO NOTHING
"""

READ_FEEDBACK_SQL = """
SELECT id, vacancy_id, evaluation_id, profile_id, profile_version, vacancy_revision,
       owner_decision, owner_reason FROM review_feedback WHERE request_id = CAST(:request AS uuid)
"""

REASONS = {
    "INTERESTED": {None},
    "NOT_INTERESTED": {"ROLE_MISMATCH", "SENIORITY", "LOCATION_OR_WORK_COUNTRY",
                       "REMOTE_POLICY", "SALARY", "TECH_STACK", "COMPANY_OR_BUSINESS_MODEL", "OTHER"},
    "NEED_MORE_INFO": {"WORK_COUNTRY_UNCONFIRMED", "SALARY_UNKNOWN", "REMOTE_POLICY_UNKNOWN",
                       "ROLE_DETAILS_UNKNOWN", "OTHER"},
}

YIELD_SQL = """
WITH active_sources AS (
  SELECT platform, region, source_id,
         CASE WHEN platform = 'GREENHOUSE' THEN 'greenhouse:' || source_id
              WHEN platform = 'LEVER' THEN 'lever:' || region || ':' || source_id END AS namespace
  FROM verified_source_registry WHERE active AND status = 'VERIFIED'
  ORDER BY platform, region, source_id LIMIT :limit OFFSET :offset
), profiles AS (
  SELECT names.profile_id, registered.profile_version, registered.criteria_hash
  FROM (VALUES ('alex-de'), ('alex-at')) AS names(profile_id)
  LEFT JOIN LATERAL (
    SELECT profile_version, criteria_hash FROM evaluation_profiles
    WHERE profile_id = names.profile_id
    ORDER BY first_seen_at DESC, profile_version DESC LIMIT 1
  ) registered ON TRUE
), current_source AS (
  SELECT vs.vacancy_id, si.id AS source_item_id,
         row_number() OVER (PARTITION BY vs.vacancy_id ORDER BY si.id DESC) AS rank
  FROM vacancy_sources vs JOIN source_items si ON si.id = vs.source_item_id
  JOIN vacancies v ON v.id = vs.vacancy_id
  WHERE si.content_digest IS NOT DISTINCT FROM v.content_digest
), source_vacancies AS (
  SELECT DISTINCT si.source_namespace, vs.vacancy_id
  FROM vacancy_sources vs JOIN source_items si ON si.id = vs.source_item_id
), current_evaluations AS (
  SELECT DISTINCT ON (si.source_namespace, e.vacancy_id, e.profile_id)
         si.source_namespace, e.vacancy_id, e.profile_id, e.profile_version,
         e.decision, e.role_classification, e.country_eligibility, e.reasons, e.unknowns
  FROM current_source cs JOIN source_items si ON si.id = cs.source_item_id
  JOIN vacancy_evaluations e ON e.vacancy_id = cs.vacancy_id AND e.source_item_id = cs.source_item_id
  JOIN profiles p ON p.profile_id = e.profile_id AND p.profile_version = e.profile_version
                 AND p.criteria_hash = e.criteria_hash
  WHERE cs.rank = 1 AND e.policy_version = 'rules-v2'
    AND e.country_evidence_policy = 'country-v1'
  ORDER BY si.source_namespace, e.vacancy_id, e.profile_id, e.evaluated_at DESC, e.id DESC
), rows AS (
  SELECT s.namespace, p.profile_id, p.profile_version,
         (SELECT count(DISTINCT si.listing_id) FROM source_items si
          WHERE si.source_namespace = s.namespace) AS observed,
         (SELECT count(*) FROM source_vacancies sv WHERE sv.source_namespace = s.namespace) AS stored,
         (SELECT count(*) FROM current_evaluations e WHERE e.source_namespace = s.namespace
          AND e.profile_id = p.profile_id) AS evaluated,
         (SELECT count(*) FROM current_evaluations e WHERE e.source_namespace = s.namespace
          AND e.profile_id = p.profile_id AND e.decision = 'EXCLUDED') AS excluded,
         (SELECT count(*) FROM current_evaluations e WHERE e.source_namespace = s.namespace
          AND e.profile_id = p.profile_id AND e.country_eligibility = 'UNKNOWN') AS country_unknown,
         (SELECT count(*) FROM current_evaluations e WHERE e.source_namespace = s.namespace
          AND e.profile_id = p.profile_id AND e.role_classification = 'UNMATCHED') AS role_unmatched,
         (SELECT count(*) FROM current_evaluations e WHERE e.source_namespace = s.namespace
          AND e.profile_id = p.profile_id AND e.country_eligibility = 'CONFIRMED'
          AND e.decision IN ('SUITABLE', 'NEEDS_REVIEW')
          AND e.role_classification IN ('PREFERRED', 'RESERVE')) AS shortlisted,
         (SELECT coalesce(jsonb_object_agg(code, occurrences), '{}'::jsonb) FROM (
            SELECT code, count(*) AS occurrences FROM current_evaluations e
            CROSS JOIN LATERAL jsonb_array_elements_text(e.reasons) AS code
            WHERE e.source_namespace = s.namespace AND e.profile_id = p.profile_id
              AND e.decision = 'EXCLUDED'
              AND code IN ('EXPLICIT_TITLE_EXCLUSION', 'EXPLICIT_ARRANGEMENT_EXCLUSION',
                           'EXPLICIT_COUNTRY_EXCLUSION') GROUP BY code) reasons) AS exclusions,
         (SELECT coalesce(jsonb_object_agg(code, occurrences), '{}'::jsonb) FROM (
            SELECT code, count(*) AS occurrences FROM current_evaluations e
            CROSS JOIN LATERAL jsonb_array_elements_text(e.unknowns) AS code
            WHERE e.source_namespace = s.namespace AND e.profile_id = p.profile_id
              GROUP BY code) unknowns) AS unknowns
  FROM active_sources s CROSS JOIN profiles p WHERE p.profile_id = :profile
), overlap AS (
  SELECT count(*) AS overlapping FROM (
    SELECT sv.vacancy_id FROM source_vacancies sv
    JOIN verified_source_registry r ON r.active AND r.status = 'VERIFIED'
      AND sv.source_namespace = CASE WHEN r.platform = 'GREENHOUSE' THEN 'greenhouse:' || r.source_id
             WHEN r.platform = 'LEVER' THEN 'lever:' || r.region || ':' || r.source_id END
    GROUP BY sv.vacancy_id HAVING count(DISTINCT sv.source_namespace) > 1
  ) shared
)
SELECT namespace, profile_id, profile_version, observed, stored, evaluated, excluded,
       country_unknown, role_unmatched, shortlisted, exclusions::text, unknowns::text,
       (SELECT overlapping FROM overlap), CURRENT_TIMESTAMP::text
FROM rows ORDER BY namespace, profile_id
"""

QUALITY_SQL = """
WITH active_sources AS (
  SELECT CASE WHEN platform = 'GREENHOUSE' THEN 'greenhouse:' || source_id
              WHEN platform = 'LEVER' THEN 'lever:' || region || ':' || source_id END AS namespace
  FROM verified_source_registry WHERE active AND status = 'VERIFIED'
), stored AS (
  SELECT DISTINCT vs.vacancy_id
  FROM vacancy_sources vs JOIN source_items si ON si.id = vs.source_item_id
  JOIN active_sources a ON a.namespace = si.source_namespace
), current_profile AS (
  SELECT profile_version, criteria_hash FROM evaluation_profiles
  WHERE profile_id = :profile ORDER BY first_seen_at DESC, profile_version DESC LIMIT 1
), current_source AS (
  SELECT vs.vacancy_id, si.id AS source_item_id,
         row_number() OVER (PARTITION BY vs.vacancy_id ORDER BY si.id DESC) AS source_rank
  FROM vacancy_sources vs JOIN source_items si ON si.id = vs.source_item_id
  JOIN vacancies v ON v.id = vs.vacancy_id
  WHERE si.content_digest IS NOT DISTINCT FROM v.content_digest
), current_evaluation AS (
  SELECT DISTINCT ON (e.vacancy_id) e.vacancy_id, e.id, e.decision,
         e.role_classification, e.country_eligibility, e.reasons, e.unknowns,
         e.original_url, v.title
  FROM vacancy_evaluations e JOIN current_profile p
    ON p.profile_version = e.profile_version AND p.criteria_hash = e.criteria_hash
  JOIN current_source cs ON cs.vacancy_id = e.vacancy_id
    AND cs.source_item_id = e.source_item_id AND cs.source_rank = 1
  JOIN source_items si ON si.id = e.source_item_id
  JOIN active_sources a ON a.namespace = si.source_namespace
  JOIN vacancies v ON v.id = e.vacancy_id
  WHERE e.profile_id = :profile AND e.policy_version = 'rules-v2'
    AND e.country_evidence_policy = 'country-v1'
    AND ((v.source_namespace LIKE 'greenhouse:%' AND si.input_version = 'greenhouse-board-v1')
      OR (v.source_namespace LIKE 'lever:%' AND si.input_version = 'lever-postings-v1'))
  ORDER BY e.vacancy_id, e.evaluated_at DESC, e.id DESC
), classified AS (
  SELECT s.vacancy_id, e.id AS evaluation_id, e.decision, e.reasons, e.unknowns,
         e.original_url, e.title,
    CASE WHEN e.id IS NULL THEN 'UNEVALUATED'
         WHEN e.role_classification = 'UNMATCHED' THEN 'ROLE_UNMATCHED'
         WHEN e.decision = 'EXCLUDED' THEN 'EXCLUDED'
         WHEN e.country_eligibility <> 'CONFIRMED' THEN 'COUNTRY_UNCONFIRMED'
         WHEN e.decision IN ('SUITABLE','NEEDS_REVIEW')
              AND e.role_classification IN ('PREFERRED','RESERVE') THEN 'SHORTLISTED'
         ELSE 'OTHER' END AS stage,
    (e.role_classification IN ('PREFERRED','RESERVE')) AS role_relevant
  FROM stored s LEFT JOIN current_evaluation e ON e.vacancy_id = s.vacancy_id
), latest_feedback AS (
  SELECT DISTINCT ON (f.vacancy_id) f.vacancy_id, f.owner_decision, f.owner_reason
  FROM review_feedback f JOIN stored s ON s.vacancy_id = f.vacancy_id
  WHERE f.profile_id = :profile ORDER BY f.vacancy_id, f.id DESC
), feedback_counts AS (
  SELECT owner_decision, owner_reason, count(*) AS n FROM latest_feedback
  GROUP BY owner_decision, owner_reason
), registered_versions AS (
  SELECT profile_version, criteria_hash,
         row_number() OVER (ORDER BY first_seen_at DESC, profile_version DESC) AS version_rank
  FROM evaluation_profiles WHERE profile_id = :profile
), comparison_stored AS (
  SELECT vacancy_id FROM stored ORDER BY vacancy_id LIMIT 1001
), version_evaluations AS (
  SELECT e.id, e.vacancy_id, rv.version_rank, e.source_item_id, e.vacancy_revision,
         e.policy_version, e.country_evidence_policy, e.decision, e.role_classification,
         e.country_eligibility,
         count(*) OVER (PARTITION BY e.vacancy_id, rv.version_rank) AS variants
  FROM comparison_stored s JOIN vacancy_evaluations e ON e.vacancy_id = s.vacancy_id
  JOIN registered_versions rv ON rv.profile_version = e.profile_version
    AND rv.criteria_hash = e.criteria_hash AND rv.version_rank <= 2
  JOIN current_source cs ON cs.vacancy_id = e.vacancy_id
    AND cs.source_item_id = e.source_item_id AND cs.source_rank = 1
  JOIN source_items si ON si.id = e.source_item_id
  JOIN active_sources a ON a.namespace = si.source_namespace
  JOIN vacancies v ON v.id = e.vacancy_id
  WHERE e.profile_id = :profile AND e.policy_version = 'rules-v2'
    AND e.country_evidence_policy = 'country-v1'
    AND ((v.source_namespace LIKE 'greenhouse:%' AND si.input_version = 'greenhouse-board-v1')
      OR (v.source_namespace LIKE 'lever:%' AND si.input_version = 'lever-postings-v1'))
), matched_versions AS (
  SELECT newer.id AS newer_id, older.id AS older_id,
         (newer.decision IN ('SUITABLE','NEEDS_REVIEW')
          AND newer.role_classification IN ('PREFERRED','RESERVE')
          AND newer.country_eligibility = 'CONFIRMED') AS newer_shortlisted,
         (older.decision IN ('SUITABLE','NEEDS_REVIEW')
          AND older.role_classification IN ('PREFERRED','RESERVE')
          AND older.country_eligibility = 'CONFIRMED') AS older_shortlisted
  FROM version_evaluations newer JOIN version_evaluations older
    ON newer.vacancy_id = older.vacancy_id AND newer.version_rank = 1
    AND older.version_rank = 2 AND newer.source_item_id = older.source_item_id
    AND newer.vacancy_revision = older.vacancy_revision
    AND newer.policy_version = older.policy_version
    AND newer.country_evidence_policy = older.country_evidence_policy
  WHERE newer.variants = 1 AND older.variants = 1
), paired_decisions AS (
  SELECT m.*,
         (SELECT f.owner_decision FROM review_feedback f
          WHERE f.evaluation_id = m.newer_id AND f.profile_id = :profile
          ORDER BY f.id DESC LIMIT 1) AS newer_feedback,
         (SELECT f.owner_decision FROM review_feedback f
          WHERE f.evaluation_id = m.older_id AND f.profile_id = :profile
          ORDER BY f.id DESC LIMIT 1) AS older_feedback
  FROM matched_versions m
), comparison_metrics AS (
  SELECT count(*) AS paired,
         count(*) FILTER (WHERE newer_feedback IS NOT NULL AND older_feedback IS NOT NULL)
           AS paired_feedback,
         count(*) FILTER (WHERE newer_shortlisted) AS newer_shortlisted,
         count(*) FILTER (WHERE older_shortlisted) AS older_shortlisted,
         count(*) FILTER (WHERE newer_feedback = 'INTERESTED'
                           AND older_feedback IS NOT NULL) AS newer_interested,
         count(*) FILTER (WHERE older_feedback = 'INTERESTED'
                           AND newer_feedback IS NOT NULL) AS older_interested
  FROM paired_decisions
), processing_versions AS (
  SELECT coalesce(jsonb_object_agg(input_version,n),'{}'::jsonb)::text AS versions FROM (
    SELECT si.input_version, count(DISTINCT s.vacancy_id) AS n
    FROM comparison_stored s JOIN current_source cs ON cs.vacancy_id = s.vacancy_id
      AND cs.source_rank = 1
    JOIN source_items si ON si.id = cs.source_item_id
    JOIN active_sources a ON a.namespace = si.source_namespace
    GROUP BY si.input_version
  ) counts
), source_overlap AS (
  SELECT count(*) AS n FROM (
    SELECT vs.vacancy_id FROM vacancy_sources vs
    JOIN source_items si ON si.id = vs.source_item_id
    JOIN active_sources a ON a.namespace = si.source_namespace
    GROUP BY vs.vacancy_id HAVING count(DISTINCT si.source_namespace) > 1
  ) shared
), examples AS (
  SELECT stage, title, original_url FROM (
    SELECT stage, title, original_url,
      row_number() OVER (PARTITION BY stage ORDER BY vacancy_id) AS example_rank
    FROM classified WHERE evaluation_id IS NOT NULL
  ) ranked WHERE example_rank <= 3
)
SELECT (SELECT count(*) FROM stored),
       (SELECT count(*) FROM classified WHERE role_relevant),
       (SELECT count(*) FROM classified WHERE stage = 'SHORTLISTED'),
       (SELECT coalesce(jsonb_object_agg(stage,n),'{}'::jsonb)::text FROM
          (SELECT stage,count(*) AS n FROM classified GROUP BY stage) stages),
       (SELECT coalesce(jsonb_object_agg(code,n),'{}'::jsonb)::text FROM
          (SELECT code,count(*) AS n FROM classified c
           CROSS JOIN LATERAL jsonb_array_elements_text(c.reasons) code
           WHERE c.decision = 'EXCLUDED' GROUP BY code) reasons),
       (SELECT coalesce(jsonb_object_agg(code,n),'{}'::jsonb)::text FROM
          (SELECT code,count(*) AS n FROM classified c
           CROSS JOIN LATERAL jsonb_array_elements_text(c.unknowns) code
           GROUP BY code) unknowns),
       (SELECT count(*) FROM latest_feedback),
       (SELECT coalesce(jsonb_agg(jsonb_build_object('decision',owner_decision,
         'reason',owner_reason,'count',n)),'[]'::jsonb)::text FROM feedback_counts),
       (SELECT coalesce(jsonb_agg(jsonb_build_object('stage',stage,'title',title,
         'url',original_url)),'[]'::jsonb)::text FROM examples),
       (SELECT n FROM source_overlap),
       (SELECT profile_version FROM current_profile), CURRENT_TIMESTAMP::text,
       (SELECT profile_version FROM registered_versions WHERE version_rank = 2),
       (SELECT criteria_hash FROM registered_versions WHERE version_rank = 1) IS DISTINCT FROM
         (SELECT criteria_hash FROM registered_versions WHERE version_rank = 2),
       (SELECT count(*) FROM comparison_stored),
       (SELECT count(DISTINCT vacancy_id) FROM version_evaluations WHERE version_rank = 1),
       (SELECT count(DISTINCT vacancy_id) FROM version_evaluations WHERE version_rank = 2),
       (SELECT paired FROM comparison_metrics),
       (SELECT paired_feedback FROM comparison_metrics),
       (SELECT newer_shortlisted FROM comparison_metrics),
       (SELECT older_shortlisted FROM comparison_metrics),
       (SELECT newer_interested FROM comparison_metrics),
       (SELECT older_interested FROM comparison_metrics),
       (SELECT versions FROM processing_versions)
"""


SOURCE_REPORT_CHANNELS = {
    "LINKEDIN": "LinkedIn",
    "HIMALAYAS": "Himalayas",
    "CAREER_BOARDS": "Verified company career boards",
}
REPORT_GEOGRAPHIES = {"ALL", "GERMANY", "AUSTRIA", "REMOTE_EUROPE", "REMOTE_WORLDWIDE", "UNKNOWN"}
MAX_REPORT_OBSERVATIONS = 5000
MAX_REPORT_EMPLOYERS = 50

SOURCE_REPORT_SQL = """
WITH report_period AS (
  SELECT statement_timestamp() AS as_of
), boards AS (
  SELECT employer_name, platform, region, source_id,
         CASE WHEN platform = 'GREENHOUSE' THEN 'greenhouse:' || source_id
              ELSE 'lever:' || region || ':' || source_id END AS namespace
  FROM verified_source_registry
  WHERE status = 'VERIFIED' AND ((platform = 'GREENHOUSE' AND region = 'global') OR platform = 'LEVER')
), source_namespaces AS (
  SELECT 'linkedin:gmail' AS namespace, 'linkedin-alert-v1' AS input_version, 'LINKEDIN' AS channel,
         NULL::text AS employer_name
  UNION ALL SELECT 'himalayas', 'himalayas-search-v1', 'HIMALAYAS', NULL
  UNION ALL SELECT namespace,
                   CASE WHEN platform = 'GREENHOUSE' THEN 'greenhouse-board-v1' ELSE 'lever-postings-v1' END,
                   'CAREER_BOARDS', employer_name FROM boards
), recent_observations AS MATERIALIZED (
  SELECT vs.vacancy_id, si.id AS source_item_id, si.fetched_at, si.content_digest,
         sn.namespace, sn.channel, sn.employer_name
  FROM source_namespaces sn
  JOIN source_items si ON si.source_namespace = sn.namespace AND si.input_version = sn.input_version
  JOIN vacancy_sources vs ON vs.source_item_id = si.id
  CROSS JOIN report_period p
  WHERE si.created_at BETWEEN p.as_of - (:days * interval '24 hours') AND p.as_of
  LIMIT (:cohort_limit + 1)
), first_channels AS MATERIALIZED (
  SELECT DISTINCT r.vacancy_id, r.channel
  FROM recent_observations r CROSS JOIN report_period p
  WHERE NOT EXISTS (
    SELECT 1 FROM vacancy_sources prior
    JOIN source_items si ON si.id = prior.source_item_id
    JOIN source_namespaces sn ON sn.namespace = si.source_namespace AND sn.input_version = si.input_version
    WHERE prior.vacancy_id = r.vacancy_id AND sn.channel = r.channel
      AND si.created_at < p.as_of - (:days * interval '24 hours')
  )
), cohort AS (
  SELECT DISTINCT ON (r.vacancy_id, r.namespace) r.*
  FROM recent_observations r JOIN first_channels f USING (vacancy_id, channel)
  ORDER BY r.vacancy_id, r.namespace, r.fetched_at DESC, r.source_item_id DESC
), evidence AS (
  SELECT c.*, a.decision = 'CANDIDATE' AS candidate,
    CASE
      WHEN c.channel = 'LINKEDIN' THEN coalesce(card.parsed_direction, card.advertised_direction)
      WHEN c.namespace = v.source_namespace AND c.content_digest = v.content_digest THEN
        CASE lower(btrim(v.location))
          WHEN 'germany' THEN 'GERMANY' WHEN 'deutschland' THEN 'GERMANY' WHEN 'berlin' THEN 'GERMANY'
          WHEN 'berlin, germany' THEN 'GERMANY'
          WHEN 'austria' THEN 'AUSTRIA' WHEN 'österreich' THEN 'AUSTRIA' WHEN 'vienna' THEN 'AUSTRIA'
          WHEN 'wien' THEN 'AUSTRIA' WHEN 'vienna, austria' THEN 'AUSTRIA'
          WHEN 'remote europe' THEN 'REMOTE_EUROPE'
          WHEN 'remote worldwide' THEN 'REMOTE_WORLDWIDE'
          WHEN 'worldwide' THEN 'REMOTE_WORLDWIDE'
        END
    END AS advertised
  FROM cohort c JOIN vacancies v ON v.id = c.vacancy_id
  LEFT JOIN gmail_alert_cards card ON card.source_item_id = c.source_item_id AND c.channel = 'LINKEDIN'
  LEFT JOIN vacancy_candidate_assessments a ON a.source_item_id = c.source_item_id
                    AND a.policy_version = 'java-backend-candidate-v2'
), geography AS (
  SELECT vacancy_id, CASE WHEN count(DISTINCT advertised) FILTER (WHERE advertised <> 'UNKNOWN') = 1
       THEN min(advertised) FILTER (WHERE advertised <> 'UNKNOWN') ELSE 'UNKNOWN' END AS advertised
  FROM evidence GROUP BY vacancy_id
), selected AS MATERIALIZED (
  SELECT e.* FROM evidence e JOIN geography g USING (vacancy_id)
  WHERE :geography = 'ALL' OR g.advertised = :geography
), membership AS (
  SELECT vacancy_id, count(DISTINCT channel) AS channels FROM selected GROUP BY vacancy_id
)
""" + current_shortlist_ctes("profile_id IN ('alex-de', 'alex-at')", for_source_report=True) + """
, shortlist AS (
  SELECT e.vacancy_id, bool_or(e.profile_id = 'alex-de') AS de, bool_or(e.profile_id = 'alex-at') AS at
  FROM current_evaluation e JOIN membership m USING (vacancy_id)
  WHERE e.evaluation_rank = 1 GROUP BY e.vacancy_id
), counts AS (
  SELECT e.channel, e.namespace, e.employer_name,
         count(DISTINCT e.vacancy_id) AS vacancies,
         count(DISTINCT e.vacancy_id) FILTER (WHERE e.candidate) AS candidates,
         count(DISTINCT e.vacancy_id) FILTER (WHERE s.de) AS "shortlistDe",
         count(DISTINCT e.vacancy_id) FILTER (WHERE s.at) AS "shortlistAt",
         count(DISTINCT e.vacancy_id) FILTER (WHERE m.channels = 1) AS "channelOnly"
  FROM selected e JOIN membership m USING (vacancy_id)
  LEFT JOIN shortlist s USING (vacancy_id)
  GROUP BY GROUPING SETS ((e.channel), (e.channel, e.namespace, e.employer_name))
), employer_rows AS (
  SELECT * FROM counts WHERE channel = 'CAREER_BOARDS' AND namespace IS NOT NULL
  ORDER BY employer_name, namespace LIMIT :employer_limit
)
SELECT jsonb_build_object(
  'total', (SELECT count(*) FROM membership),
  'overlap', (SELECT count(*) FROM membership WHERE channels > 1),
  'asOf', (SELECT as_of FROM report_period),
  'cohortExceeded', (SELECT count(*) > :cohort_limit FROM recent_observations),
  'employersTruncated', (SELECT count(*) > :employer_limit FROM counts
                        WHERE channel = 'CAREER_BOARDS' AND namespace IS NOT NULL),
  'channels', (SELECT coalesce(json_agg(counts ORDER BY channel), '[]') FROM counts WHERE namespace IS NULL),
  'employers', (SELECT coalesce(json_agg(employer_rows ORDER BY employer_name, namespace), '[]') FROM employer_rows)
)::text
"""


def response(status, body, content_type="application/json"):
    payload = body if isinstance(body, str) else json.dumps(body, separators=(",", ":"))
    auth_origin = os.environ.get("COGNITO_DOMAIN", "")
    return {"statusCode": status, "headers": {
        "Content-Type": content_type, "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer",
        "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; "
                                   f"connect-src 'self' {auth_origin}; form-action 'none'; "
                                   "base-uri 'none'; frame-ancestors 'none'"
    }, "body": payload}


def authorized(event):
    claims = (event.get("requestContext", {}).get("authorizer", {}).get("jwt", {})
              .get("claims", {}))
    groups = claims.get("cognito:groups", "")
    if isinstance(groups, str):
        groups = groups.strip("[]").replace('"', "").split(",")
    return claims.get("sub") and "job-agent-owner" in {g.strip() for g in groups}


def integer(value, low, high):
    if not isinstance(value, str) or not value.isdecimal():
        raise ValueError("Invalid pagination")
    number = int(value)
    if number < low or number > high:
        raise ValueError("Invalid pagination")
    return number


def decode_row(row):
    values = [cell_value(cell) for cell in row]
    keys = ("evaluationId", "vacancyId", "title", "employer", "url", "decision",
            "roleClassification", "reasons", "unknowns", "profileVersion", "revision",
            "feedbackId", "ownerDecision", "ownerReason", "feedbackEvaluationId")
    item = dict(zip(keys, values))
    url = urlsplit(item["url"] or "")
    if url.scheme != "https" or not url.hostname or url.username or url.password:
        return None
    for key in ("reasons", "unknowns"):
        raw = json.loads(item[key] or "[]")
        item[key] = [str(value)[:80] for value in raw[:20] if isinstance(value, str)]
    return item


def cell_value(cell):
    return None if cell.get("isNull") else next(iter(cell.values()), None)


def read_source_report(event, client):
    if not authorized(event):
        return response(403, {"error": "Owner access required"})
    query = event.get("queryStringParameters") or {}
    days = query.get("days", "30")
    geography = query.get("geography", "ALL")
    if days not in ("7", "30") or geography not in REPORT_GEOGRAPHIES:
        return response(400, {"error": "Choose a 7- or 30-day period and a supported geography"})
    try:
        result = client.execute_statement(
            resourceArn=os.environ["DB_CLUSTER_ARN"], secretArn=os.environ["DB_READER_SECRET_ARN"],
            database="job_agent", sql=SOURCE_REPORT_SQL, parameters=[
                parameter("days", int(days)), parameter("geography", geography),
                parameter("cohort_limit", MAX_REPORT_OBSERVATIONS), parameter("employer_limit", MAX_REPORT_EMPLOYERS)])
        report = json.loads(cell_value(result["records"][0][0]))
        if report.pop("cohortExceeded"):
            return response(422, {"error": "The period exceeds the source report capacity."})
        counts = ("vacancies", "candidates", "shortlistDe", "shortlistAt", "channelOnly")
        rows = {item["channel"]: item for item in report["channels"]}
        report["channels"] = [
            {"channel": channel, "label": label, **{key: rows.get(channel, {}).get(key, 0) for key in counts}}
            for channel, label in SOURCE_REPORT_CHANNELS.items()]
        for employer in report["employers"]:
            employer["label"] = f"{employer.pop('employer_name')} ({employer.pop('namespace')})"
        return response(200, report)
    except Exception:  # Avoid exposing Data API diagnostics or database contents.
        return response(503, {"error": "Source report is temporarily unavailable"})


def read_page(event, client):
    if not authorized(event):
        return response(403, {"error": "Owner access required"})
    query = event.get("queryStringParameters") or {}
    profile = query.get("profile", "alex-de")
    if profile not in ("alex-de", "alex-at"):
        return response(400, {"error": "Choose Germany or Austria"})
    try:
        limit = integer(query.get("limit", "20"), 1, MAX_PAGE)
        offset = integer(query.get("offset", "0"), 0, MAX_OFFSET)
    except ValueError:
        return response(400, {"error": "Invalid pagination"})
    try:
        result = client.execute_statement(
            resourceArn=os.environ["DB_CLUSTER_ARN"], secretArn=os.environ["DB_READER_SECRET_ARN"],
            database="job_agent", sql=SQL,
            parameters=[{"name": "profile", "value": {"stringValue": profile}},
                        {"name": "limit", "value": {"longValue": limit + 1}},
                        {"name": "offset", "value": {"longValue": offset}}])
        records = result.get("records", [])
        items = [item for row in records if (item := decode_row(row))]
        body = {"profile": profile, "items": items[:limit],
                "nextOffset": offset + limit if len(records) > limit else None}
        if len(json.dumps(body).encode("utf-8")) > MAX_BODY:
            return response(502, {"error": "Page is too large; choose fewer entries"})
        return response(200, body)
    except Exception:  # Do not log SQL, credentials, token or vacancy data.
        return response(503, {"error": "Vacancies are temporarily unavailable"})


def read_linkedin(event, client):
    if not authorized(event):
        return response(403, {"error": "Owner access required"})
    query = event.get("queryStringParameters") or {}
    profile = query.get("profile", "alex-de")
    if profile not in ("alex-de", "alex-at"):
        return response(400, {"error": "Choose Germany or Austria"})
    try:
        limit = integer(query.get("limit", "20"), 1, MAX_PAGE)
        offset = integer(query.get("offset", "0"), 0, MAX_OFFSET)
    except ValueError:
        return response(400, {"error": "Invalid pagination"})
    try:
        result = client.execute_statement(
            resourceArn=os.environ["DB_CLUSTER_ARN"], secretArn=os.environ["DB_READER_SECRET_ARN"],
            database="job_agent", sql=LINKEDIN_SQL,
            parameters=[{"name": "profile", "value": {"stringValue": profile}},
                        {"name": "limit", "value": {"longValue": limit + 1}},
                        {"name": "offset", "value": {"longValue": offset}}])
        rows = result.get("records", [])
        items = []
        for row in rows[:limit]:
            vacancy_id, title, employer, job_id, decision, role, reasons, unknowns, direction, evidence, advertised_employer = (
                cell_value(cell) for cell in row)
            unknown_codes = json.loads(unknowns or "[]")[:20] if decision else [
                "ROLE_NOT_ESTABLISHED", "WORK_ELIGIBILITY_UNCONFIRMED", "SALARY_UNKNOWN"]
            items.append({"vacancyId": vacancy_id, "title": title,
                          "employer": None if employer == "Employer unknown" else employer,
                          "advertisedEmployer": advertised_employer,
                          "url": "https://www.linkedin.com/jobs/view/" + job_id + "/",
                          "decision": decision or "PENDING_EVALUATION",
                          "advertisedDirection": direction,
                          "directionEvidence": evidence,
                          "role": role or "UNKNOWN",
                          "reasons": json.loads(reasons or "[]")[:20],
                          "unknowns": unknown_codes})
        body = {"profile": profile, "items": items,
                "nextOffset": offset + limit if len(rows) > limit else None}
        if len(json.dumps(body).encode("utf-8")) > MAX_BODY:
            return response(502, {"error": "Page is too large; choose fewer entries"})
        return response(200, body)
    except Exception:  # No source text, SQL, credentials or token in logs.
        return response(503, {"error": "LinkedIn observations are temporarily unavailable"})


def read_yield(event, client):
    if not authorized(event):
        return response(403, {"error": "Owner access required"})
    query = event.get("queryStringParameters") or {}
    profile = query.get("profile", "alex-de")
    if profile not in ("alex-de", "alex-at"):
        return response(400, {"error": "Choose Germany or Austria"})
    try:
        limit = integer(query.get("limit", "20"), 1, MAX_PAGE)
        offset = integer(query.get("offset", "0"), 0, MAX_OFFSET)
    except ValueError:
        return response(400, {"error": "Invalid pagination"})
    try:
        result = client.execute_statement(
            resourceArn=os.environ["DB_CLUSTER_ARN"], secretArn=os.environ["DB_READER_SECRET_ARN"],
            database="job_agent", sql=YIELD_SQL,
            parameters=[{"name": "profile", "value": {"stringValue": profile}},
                        {"name": "limit", "value": {"longValue": limit + 1}},
                        {"name": "offset", "value": {"longValue": offset}}])
        records = result.get("records", [])
        keys = ("source", "profile", "profileVersion", "observed", "stored", "evaluated",
                "excluded", "countryUnknown", "roleUnmatched", "shortlisted", "exclusions",
                "unknowns", "crossSourceOverlap", "asOf")
        rows = []
        for record in records[:limit]:
            row = dict(zip(keys, (cell_value(cell) for cell in record)))
            for key in ("exclusions", "unknowns"):
                row[key] = json.loads(row[key] or "{}")
            rows.append(row)
        body = {"window": "All stored observations; current active sources and latest selected profile version",
                "policyVersion": "rules-v2", "countryEvidencePolicy": "country-v1",
                "rows": rows, "nextOffset": offset + limit if len(records) > limit else None}
        if len(json.dumps(body).encode("utf-8")) > MAX_BODY:
            return response(502, {"error": "Diagnostic page is too large"})
        return response(200, body)
    except Exception:  # Never log SQL, tokens, credentials or source data.
        return response(503, {"error": "Diagnostics are temporarily unavailable"})


def read_quality(event, client):
    if not authorized(event):
        return response(403, {"error": "Owner access required"})
    profile = (event.get("queryStringParameters") or {}).get("profile", "alex-de")
    if profile not in ("alex-de", "alex-at"):
        return response(400, {"error": "Choose Germany or Austria"})
    try:
        result = client.execute_statement(
            resourceArn=os.environ["DB_CLUSTER_ARN"], secretArn=os.environ["DB_READER_SECRET_ARN"],
            database="job_agent", sql=QUALITY_SQL,
            parameters=[{"name": "profile", "value": {"stringValue": profile}}])
        values = [cell_value(cell) for cell in result["records"][0]]
        keys = ("stored", "roleRelevant", "shortlisted", "stages", "exclusions", "unknowns",
                "feedbackVacancies", "feedback", "examples", "crossSourceOverlap", "profileVersion",
                "asOf", "previousVersion", "criteriaChanged", "comparisonSample", "currentEvaluated",
                "previousEvaluated", "matchedVacancies", "pairedFeedback", "currentShortlisted",
                "previousShortlisted", "currentInterested", "previousInterested", "sourceVersions")
        body = dict(zip(keys, values))
        body["profile"] = profile
        for key in ("stages", "exclusions", "unknowns", "feedback", "examples", "sourceVersions"):
            body[key] = json.loads(body[key])
        body["examples"] = [example for example in body["examples"] if
                            (url := urlsplit(example["url"] or "")).scheme == "https"
                            and url.hostname and not url.username and not url.password]
        body["policyVersion"] = "rules-v2"
        body["countryEvidencePolicy"] = "country-v1"
        comparison = {key: body[key] for key in ("previousVersion", "comparisonSample",
                      "currentEvaluated", "previousEvaluated", "matchedVacancies", "pairedFeedback")}
        if body["comparisonSample"] > 1000:
            summary = "More than 1,000 stored vacancies: version comparison is bounded to a partial sample."
        elif body["previousVersion"] is None:
            summary = "No earlier registered profile version exists for this country."
        elif not body["criteriaChanged"]:
            summary = "The latest two profile versions have the same criteria hash; there is no criteria change to compare."
        elif body["matchedVacancies"] < MIN_COMPARABLE_FEEDBACK:
            summary = "Too few vacancies have both versions on the same source snapshot and policy."
        elif body["pairedFeedback"] < MIN_COMPARABLE_FEEDBACK:
            summary = "Too few matched vacancies have owner decisions on both evaluation versions."
        else:
            summary = (f"On {body['matchedVacancies']} matched vacancies, shortlisted counts were "
                       f"{body['previousShortlisted']} before and {body['currentShortlisted']} now. "
                       f"Among {body['pairedFeedback']} with decisions on both versions, "
                       f"interested decisions were {body['previousInterested']} before and "
                       f"{body['currentInterested']} now. This is descriptive, not a measured improvement.")
        comparison["summary"] = summary
        comparison["descriptive"] = body["comparisonSample"] <= 1000 and body["previousVersion"] is not None \
            and body["criteriaChanged"] and body["matchedVacancies"] >= MIN_COMPARABLE_FEEDBACK \
            and body["pairedFeedback"] >= MIN_COMPARABLE_FEEDBACK
        body["comparison"] = comparison
        for key in ("previousVersion", "criteriaChanged", "comparisonSample", "currentEvaluated",
                    "previousEvaluated", "matchedVacancies", "pairedFeedback", "currentShortlisted",
                    "previousShortlisted", "currentInterested", "previousInterested"):
            del body[key]
        if len(json.dumps(body).encode("utf-8")) > MAX_BODY:
            return response(502, {"error": "Diagnostic response is too large"})
        return response(200, body)
    except Exception:  # Never log SQL, tokens, credentials or source data.
        return response(503, {"error": "Diagnostics are temporarily unavailable"})


def feedback_input(event):
    body = event.get("body")
    if (event.get("isBase64Encoded") or not isinstance(body, str)
            or len(body.encode("utf-8")) > 2048):
        raise ValueError("Invalid feedback request")

    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate feedback field")
            result[key] = value
        return result

    data = json.loads(body, object_pairs_hook=unique_pairs)
    if not isinstance(data, dict) or set(data) != {
            "requestId", "profile", "vacancyId", "evaluationId", "profileVersion",
            "revision", "decision", "reason"}:
        raise ValueError("Invalid feedback fields")
    try:
        request_id = str(uuid.UUID(data["requestId"], version=4))
    except (ValueError, TypeError, AttributeError) as error:
        raise ValueError("Invalid feedback request identity") from error
    if data["requestId"] != request_id or data["profile"] not in ("alex-de", "alex-at"):
        raise ValueError("Invalid feedback identity")
    if (type(data["vacancyId"]) is not int or data["vacancyId"] < 1
            or type(data["evaluationId"]) is not int or data["evaluationId"] < 1
            or not isinstance(data["profileVersion"], str)
            or not 1 <= len(data["profileVersion"]) <= 40
            or not all(char.isalnum() or char in "._-" for char in data["profileVersion"])
            or not isinstance(data["revision"], str)
            or len(data["revision"]) != 64
            or any(char not in "0123456789abcdef" for char in data["revision"])
            or data["decision"] not in REASONS
            or data["reason"] not in REASONS[data["decision"]]):
        raise ValueError("Invalid feedback decision or evaluation identity")
    return data


def parameter(name, value):
    if value is None:
        encoded = {"isNull": True}
    elif type(value) is int:
        encoded = {"longValue": value}
    else:
        encoded = {"stringValue": value}
    return {"name": name, "value": encoded}


def write_feedback(event, client):
    if not authorized(event):
        return response(403, {"error": "Owner access required"})
    try:
        data = feedback_input(event)
    except (ValueError, TypeError, UnicodeError):
        return response(400, {"error": "Choose a valid decision and reason for this evaluation"})
    request = parameter("request", data["requestId"])
    try:
        connection = {"resourceArn": os.environ["DB_CLUSTER_ARN"],
                      "secretArn": os.environ["DB_FEEDBACK_SECRET_ARN"], "database": "job_agent"}
        inserted = client.execute_statement(**connection, sql=INSERT_FEEDBACK_SQL, parameters=[
            request, parameter("profile", data["profile"]),
            parameter("vacancy", data["vacancyId"]), parameter("evaluation", data["evaluationId"]),
            parameter("version", data["profileVersion"]), parameter("revision", data["revision"]),
            parameter("decision", data["decision"]), parameter("reason", data["reason"])])
        found = client.execute_statement(**connection, sql=READ_FEEDBACK_SQL, parameters=[request])
        records = found.get("records", [])
        if len(records) != 1:
            return response(409, {"error": "Evaluation is no longer current; reload the page"})
        values = [cell_value(cell) for cell in records[0]]
        expected = [data[key] for key in ("vacancyId", "evaluationId", "profile", "profileVersion",
                                           "revision", "decision", "reason")]
        if values[1:] != expected:
            return response(409, {"error": "Request identity was already used for another decision"})
        return response(201 if inserted.get("numberOfRecordsUpdated") == 1 else 200,
                        {"feedbackId": values[0], "decision": values[6], "reason": values[7],
                         "evaluationId": values[2]})
    except Exception:  # Do not log tokens, SQL, vacancy data or database diagnostics.
        return response(503, {"error": "Decision could not be confirmed; retry the same request"})


def handler(event, _context):
    path = event.get("rawPath", "/")
    if path == "/":
        return response(200, Path(__file__).with_name("index.html").read_text(), "text/html; charset=utf-8")
    if path == "/app.js":
        return response(200, Path(__file__).with_name("app.js").read_text(), "text/javascript; charset=utf-8")
    if path == "/style.css":
        return response(200, Path(__file__).with_name("style.css").read_text(), "text/css; charset=utf-8")
    if path == "/config":
        return response(200, {"clientId": os.environ["COGNITO_CLIENT_ID"],
                              "domain": os.environ["COGNITO_DOMAIN"],
                              "redirect": os.environ["PAGE_URL"],
                              "feedbackEnabled": os.environ.get("PAGE_FEEDBACK_ENABLED") == "true"})
    if path == "/source-report":
        if not authorized(event):
            return response(403, {"error": "Owner access required"})
        return read_source_report(event, boto3.client("rds-data"))
    if path == "/vacancies":
        return read_page(event, boto3.client("rds-data"))
    if path == "/linkedin":
        return read_linkedin(event, boto3.client("rds-data"))
    if path == "/yield":
        return read_yield(event, boto3.client("rds-data"))
    if path == "/quality":
        return read_quality(event, boto3.client("rds-data"))
    if path == "/feedback" and event.get("requestContext", {}).get("http", {}).get("method") == "POST":
        if not authorized(event):
            return response(403, {"error": "Owner access required"})
        return write_feedback(event, boto3.client("rds-data"))
    return response(404, {"error": "Not found"})
