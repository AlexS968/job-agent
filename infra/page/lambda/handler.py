"""Read-only, owner-gated vacancy projection for API Gateway HTTP API."""

import json
import os
from pathlib import Path
from urllib.parse import urlsplit

import boto3

MAX_PAGE = 20
MAX_OFFSET = 1000
MAX_BODY = 64_000

SQL = """
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
         e.original_url, e.decision, e.role_classification, e.reasons,
         e.unknowns, e.preference_rank, e.evaluated_at,
         v.title, em.name AS employer,
         row_number() OVER (PARTITION BY e.vacancy_id ORDER BY e.evaluated_at DESC,
                           e.id DESC) AS evaluation_rank
  FROM vacancy_evaluations e
  JOIN current_profile cp ON cp.profile_id = e.profile_id AND cp.profile_version = e.profile_version
      AND cp.criteria_hash = e.criteria_hash AND cp.profile_rank = 1
  JOIN current_source cs ON cs.vacancy_id = e.vacancy_id
      AND cs.source_item_id = e.source_item_id AND cs.source_rank = 1
  JOIN vacancies v ON v.id = e.vacancy_id
  JOIN employers em ON em.id = v.employer_id
  JOIN source_items si ON si.id = e.source_item_id
  WHERE e.policy_version = 'rules-v1'
    AND e.decision IN ('SUITABLE', 'NEEDS_REVIEW')
    AND e.role_classification IN ('PREFERRED', 'RESERVE')
    AND ((v.source_namespace LIKE 'greenhouse:%' AND si.input_version = 'greenhouse-board-v1')
      OR (v.source_namespace LIKE 'lever:%' AND si.input_version = 'lever-postings-v1'))
)
SELECT id, vacancy_id, title, employer, original_url, decision, role_classification,
       reasons, unknowns, profile_version, vacancy_revision
FROM current_evaluation WHERE evaluation_rank = 1
ORDER BY CASE decision WHEN 'SUITABLE' THEN 0 ELSE 1 END, preference_rank,
         CASE role_classification WHEN 'PREFERRED' THEN 0 ELSE 1 END, vacancy_id
LIMIT :limit OFFSET :offset
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
    values = [next(iter(cell.values()), None) for cell in row]
    keys = ("evaluationId", "vacancyId", "title", "employer", "url", "decision",
            "roleClassification", "reasons", "unknowns", "profileVersion", "revision")
    item = dict(zip(keys, values))
    url = urlsplit(item["url"] or "")
    if url.scheme != "https" or not url.hostname or url.username or url.password:
        return None
    for key in ("reasons", "unknowns"):
        raw = json.loads(item[key] or "[]")
        item[key] = [str(value)[:80] for value in raw[:20] if isinstance(value, str)]
    return item


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
                              "redirect": os.environ["PAGE_URL"]})
    if path == "/vacancies":
        return read_page(event, boto3.client("rds-data"))
    return response(404, {"error": "Not found"})
