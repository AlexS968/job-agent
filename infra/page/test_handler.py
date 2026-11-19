"""Offline contract tests for the owner-only read boundary."""

import importlib.util
import json
import os
from pathlib import Path
import sys
import types
import unittest
import uuid
from unittest.mock import patch

sys.modules.setdefault("boto3", types.SimpleNamespace(client=lambda _: None))
spec = importlib.util.spec_from_file_location("page_handler", Path(__file__).parent / "lambda" / "handler.py")
page = importlib.util.module_from_spec(spec)
spec.loader.exec_module(page)


def event(profile="alex-de", limit="20", offset="0", owner=True):
    claims = {"sub": "synthetic-owner", "cognito:groups": "[job-agent-owner]"} if owner else {}
    return {"rawPath": "/vacancies", "queryStringParameters":
            {"profile": profile, "limit": limit, "offset": offset},
            "requestContext": {"authorizer": {"jwt": {"claims": claims}}}}


def row(url="https://jobs.example.test/posting/1", reasons=None, unknowns=None):
    values = [1, 10, "Senior Java Engineer", "Example Employer", url, "NEEDS_REVIEW", "PREFERRED",
              json.dumps(reasons or ["ROLE_PREFERRED"]),
              json.dumps(unknowns or ["WORK_ELIGIBILITY_UNCONFIRMED", "SALARY_UNKNOWN"]),
              "v1", "a" * 64, None, None, None, None]
    return [{"isNull": True} if v is None else {"longValue": v} if isinstance(v, int)
            else {"stringValue": v} for v in values]


class FakeDataApi:
    def __init__(self, records=None, fail=False):
        self.records = records if records is not None else []
        self.fail = fail
        self.calls = []

    def execute_statement(self, **kwargs):
        self.calls.append(kwargs)
        if self.fail:
            raise RuntimeError("synthetic secret value must not leak")
        return {"records": self.records}


class PageTest(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {"DB_CLUSTER_ARN": "cluster-test",
                                           "DB_READER_SECRET_ARN": "secret-test"})
        self.env.start()
        self.addCleanup(self.env.stop)

    def test_missing_owner_group_never_queries_database(self):
        db = FakeDataApi([row()])
        result = page.read_page(event(owner=False), db)
        self.assertEqual(403, result["statusCode"])
        self.assertEqual([], db.calls)

    def test_linkedin_observation_is_owner_only_and_not_a_confirmed_recommendation(self):
        values = [34, "Java Engineer", "Employer unknown", "123456", "NEEDS_REVIEW", "PREFERRED",
                  '["JAVA_BACKEND_CANDIDATE"]', '["WORK_ELIGIBILITY_UNCONFIRMED","SALARY_UNKNOWN"]',
                  "REMOTE_EUROPE", "Remote Europe", "Synthetic employer"]
        row_values = [{"longValue": value} if isinstance(value, int) else {"stringValue": value}
                      for value in values]
        db = FakeDataApi([row_values])
        request = event(profile="alex-at", limit="1")
        self.assertEqual(403, page.read_linkedin(event(owner=False), db)["statusCode"])
        self.assertEqual([], db.calls)
        result = json.loads(page.read_linkedin(request, db)["body"])
        self.assertEqual("alex-at", result["profile"])
        self.assertEqual("https://www.linkedin.com/jobs/view/123456/", result["items"][0]["url"])
        self.assertIsNone(result["items"][0]["employer"])
        self.assertEqual("Synthetic employer", result["items"][0]["advertisedEmployer"])
        self.assertIn("WORK_ELIGIBILITY_UNCONFIRMED", result["items"][0]["unknowns"])
        self.assertEqual("REMOTE_EUROPE", result["items"][0]["advertisedDirection"])
        self.assertEqual("Remote Europe", result["items"][0]["directionEvidence"])
        self.assertEqual("alex-at", db.calls[0]["parameters"][0]["value"]["stringValue"])
        self.assertNotIn("linkedin", page.SQL)  # The confirmed recommendation query stays separate.
        pending = list(row_values)
        pending[4:8] = [{"isNull": True}] * 4
        db.records = [pending]
        pending_result = json.loads(page.read_linkedin(request, db)["body"])
        self.assertEqual("PENDING_EVALUATION", pending_result["items"][0]["decision"])
        self.assertIn("SALARY_UNKNOWN", pending_result["items"][0]["unknowns"])

    def test_profiles_are_parameterized_and_separate(self):
        db = FakeDataApi([row()])
        for profile in ("alex-de", "alex-at"):
            result = page.read_page(event(profile=profile), db)
            self.assertEqual(profile, json.loads(result["body"])["profile"])
        self.assertEqual(["alex-de", "alex-at"],
                         [call["parameters"][0]["value"]["stringValue"] for call in db.calls])
        self.assertNotIn("alex-de", page.SQL)
        self.assertEqual(400, page.read_page(event(profile="unknown"), db)["statusCode"])
        self.assertEqual(2, len(db.calls))

    def test_pagination_empty_result_and_limits(self):
        db = FakeDataApi([row(), row(), row()])
        result = json.loads(page.read_page(event(limit="2", offset="4"), db)["body"])
        self.assertEqual(2, len(result["items"]))
        self.assertEqual(6, result["nextOffset"])
        self.assertEqual(3, db.calls[0]["parameters"][1]["value"]["longValue"])
        db.records = []
        self.assertEqual({"profile": "alex-de", "items": [], "nextOffset": None},
                         json.loads(page.read_page(event(), db)["body"]))
        for bad in ("0", "21", "-1", "1e2"):
            self.assertEqual(400, page.read_page(event(limit=bad), db)["statusCode"])

    def test_evidence_unknowns_and_unsafe_link(self):
        db = FakeDataApi([row(), row(url="javascript:alert(1)")])
        result = json.loads(page.read_page(event(), db)["body"])
        self.assertEqual(["ROLE_PREFERRED"], result["items"][0]["reasons"])
        self.assertEqual(["WORK_ELIGIBILITY_UNCONFIRMED", "SALARY_UNKNOWN"],
                         result["items"][0]["unknowns"])
        self.assertEqual(1, len(result["items"]))
        self.assertIn("source_rank = 1", page.SQL)
        self.assertIn("role_classification IN ('PREFERRED', 'RESERVE')", page.SQL)

    def test_database_failure_is_safe_and_non_successful(self):
        result = page.read_page(event(), FakeDataApi(fail=True))
        self.assertEqual(503, result["statusCode"])
        self.assertNotIn("synthetic secret", result["body"])

    def test_null_database_cell_does_not_become_boolean_true(self):
        item = row()
        item[3] = {"isNull": True}
        self.assertIsNone(page.decode_row(item)["employer"])

    def test_diagnostics_are_owner_only_paginated_and_keep_unknowns_separate(self):
        values = ["greenhouse:demo", "alex-at", "v1", 3, 2, 1, 0, 1, 0, 0,
                  "{}", '{"WORK_ELIGIBILITY_UNCONFIRMED":1}', 1, "2026-11-14 12:00:00+00"]
        record = [{"longValue": value} if isinstance(value, int) else {"stringValue": value}
                  for value in values]
        db = FakeDataApi([record, record, record])
        denied = {**event(owner=False), "rawPath": "/yield"}
        self.assertEqual(403, page.read_yield(denied, db)["statusCode"])
        self.assertEqual([], db.calls)
        permitted = {**event(), "rawPath": "/yield"}
        permitted["queryStringParameters"] = {"limit": "1", "offset": "0"}
        result = json.loads(page.read_yield(permitted, db)["body"])
        self.assertEqual(1, result["rows"][0]["unknowns"]["WORK_ELIGIBILITY_UNCONFIRMED"])
        self.assertEqual({}, result["rows"][0]["exclusions"])
        self.assertEqual(1, result["nextOffset"])
        self.assertEqual("alex-de", db.calls[0]["parameters"][0]["value"]["stringValue"])
        self.assertEqual(2, db.calls[0]["parameters"][1]["value"]["longValue"])
        self.assertIn("active", result["window"])

    def test_quality_is_owner_only_profile_scoped_and_bounds_examples(self):
        values = [3, 2, 1, '{"SHORTLISTED":1,"COUNTRY_UNCONFIRMED":1,"UNEVALUATED":1}',
                  '{"EXPLICIT_COUNTRY_EXCLUSION":1}', '{"SALARY_UNKNOWN":2}', 1,
                  '[{"decision":"NOT_INTERESTED","reason":"SALARY","count":1}]',
                  '[{"stage":"SHORTLISTED","title":"Java Engineer","url":"https://jobs.example.test/1"},'
                  '{"stage":"EXCLUDED","title":"Unsafe","url":"javascript:alert(1)"}]',
                  1, "v2", "2026-11-15 12:00:00+00", None, False, 3, 1, 0, 0, 0,
                  0, 0, 0, 0, '{"greenhouse-board-v1":3}']
        record = [{"isNull": True} if value is None else {"booleanValue": value}
                  if isinstance(value, bool) else {"longValue": value} if isinstance(value, int)
                  else {"stringValue": value}
                  for value in values]
        db = FakeDataApi([record])
        self.assertEqual(403, page.read_quality(event(owner=False), db)["statusCode"])
        self.assertEqual([], db.calls)
        self.assertEqual(400, page.read_quality(event(profile="other"), db)["statusCode"])
        for profile in ("alex-de", "alex-at"):
            result = json.loads(page.read_quality(event(profile=profile), db)["body"])
            self.assertEqual(profile, result["profile"])
            self.assertEqual(3, result["stored"])
            self.assertEqual(1, result["feedbackVacancies"])
            self.assertEqual(1, len(result["examples"]))
            self.assertIn("No earlier registered profile version", result["comparison"]["summary"])
            self.assertEqual({"greenhouse-board-v1": 3}, result["sourceVersions"])
        self.assertEqual(["alex-de", "alex-at"],
                         [call["parameters"][0]["value"]["stringValue"] for call in db.calls])
        self.assertNotIn("alex-de", page.QUALITY_SQL)
        record[12] = {"stringValue": "v1"}
        record[13] = {"booleanValue": True}
        record[16] = {"longValue": 2}
        record[17] = {"longValue": 2}
        small = json.loads(page.read_quality(event(), db)["body"])["comparison"]
        self.assertIn("Too few vacancies", small["summary"])
        self.assertEqual(2, small["matchedVacancies"])
        record[13] = {"booleanValue": False}
        self.assertIn("same criteria hash", json.loads(page.read_quality(event(), db)["body"])
                      ["comparison"]["summary"])
        record[13] = {"booleanValue": True}
        record[14] = {"longValue": 20}
        record[15] = {"longValue": 12}
        record[16] = {"longValue": 11}
        record[17] = {"longValue": 10}
        record[18] = {"longValue": 1}
        self.assertIn("Too few matched vacancies", json.loads(page.read_quality(event(), db)["body"])
                      ["comparison"]["summary"])
        record[18] = {"longValue": 10}
        record[21] = {"longValue": 3}
        record[22] = {"longValue": 4}
        descriptive = json.loads(page.read_quality(event(), db)["body"])["comparison"]
        self.assertTrue(descriptive["descriptive"])
        self.assertIn("interested decisions were 4 before and 3 now", descriptive["summary"])
        record[14] = {"longValue": 1001}
        bounded = json.loads(page.read_quality(event(), db)["body"])["comparison"]
        self.assertFalse(bounded["descriptive"])
        self.assertIn("bounded to a partial sample", bounded["summary"])
        db.records = []
        self.assertEqual(503, page.read_quality(event(), db)["statusCode"])

    def feedback_event(self, **changes):
        values = {"requestId": str(uuid.uuid4()), "profile": "alex-de", "vacancyId": 10,
                  "evaluationId": 1, "profileVersion": "v1", "revision": "a" * 64,
                  "decision": "INTERESTED", "reason": None}
        values.update(changes)
        return {**event(), "rawPath": "/feedback", "body": json.dumps(values),
                "requestContext": {**event()["requestContext"], "http": {"method": "POST"}}}

    def test_feedback_auth_and_decision_reason_contract(self):
        db = FakeDataApi()
        denied = self.feedback_event()
        denied["requestContext"]["authorizer"]["jwt"]["claims"] = {}
        self.assertEqual(403, page.write_feedback(denied, db)["statusCode"])
        self.assertEqual([], db.calls)
        valid = self.feedback_event(decision="NOT_INTERESTED", reason="SALARY")
        self.assertEqual("NOT_INTERESTED", page.feedback_input(valid)["decision"])
        for wrong in (self.feedback_event(decision="NOT_INTERESTED", reason=None),
                      self.feedback_event(decision="NEED_MORE_INFO", reason="TECH_STACK"),
                      self.feedback_event(decision="INTERESTED", reason="OTHER"),
                      self.feedback_event(profile="other"),
                      self.feedback_event(vacancyId=True),
                      self.feedback_event(revision="wrong")):
            self.assertEqual(400, page.write_feedback(wrong, db)["statusCode"])
        self.assertEqual([], db.calls)

    def test_feedback_request_is_parameterized_and_retry_uses_same_identity(self):
        request = self.feedback_event(decision="NEED_MORE_INFO", reason="SALARY_UNKNOWN")
        payload = json.loads(request["body"])
        values = [3, 10, 1, "alex-de", "v1", "a" * 64, "NEED_MORE_INFO", "SALARY_UNKNOWN"]
        record = [{"longValue": value} if isinstance(value, int) else {"stringValue": value}
                  for value in values]
        class FeedbackDb:
            def __init__(self):
                self.calls = []
            def execute_statement(self, **kwargs):
                self.calls.append(kwargs)
                if len(self.calls) % 2:
                    return {"numberOfRecordsUpdated": 1 if len(self.calls) == 1 else 0}
                return {"records": [record]}
        db = FeedbackDb()
        with patch.dict(os.environ, {"DB_FEEDBACK_SECRET_ARN": "writer-secret-test"}):
            first = page.write_feedback(request, db)
            second = page.write_feedback(request, db)
        self.assertEqual(201, first["statusCode"])
        self.assertEqual(200, second["statusCode"])
        self.assertEqual(3, json.loads(first["body"])["feedbackId"])
        self.assertEqual(payload["requestId"], db.calls[0]["parameters"][0]["value"]["stringValue"])
        self.assertEqual("writer-secret-test", db.calls[0]["secretArn"])
        self.assertNotIn("alex-de", db.calls[0]["sql"])
        changed = {**request, "body": json.dumps({**payload, "decision": "INTERESTED", "reason": None})}
        with patch.dict(os.environ, {"DB_FEEDBACK_SECRET_ARN": "writer-secret-test"}):
            self.assertEqual(409, page.write_feedback(changed, db)["statusCode"])

    def test_feedback_stale_or_failed_write_never_claims_success(self):
        request = self.feedback_event()
        class StaleDb:
            def execute_statement(self, **kwargs):
                return {"numberOfRecordsUpdated": 0} if "INSERT INTO" in kwargs["sql"] else {"records": []}
        with patch.dict(os.environ, {"DB_FEEDBACK_SECRET_ARN": "writer-secret-test"}):
            self.assertEqual(409, page.write_feedback(request, StaleDb())["statusCode"])
            failed = page.write_feedback(request, FakeDataApi(fail=True))
        self.assertEqual(503, failed["statusCode"])
        self.assertNotIn("synthetic secret", failed["body"])


if __name__ == "__main__":
    unittest.main()
