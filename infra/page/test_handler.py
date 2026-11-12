"""Offline contract tests for the owner-only read boundary."""

import importlib.util
import json
import os
from pathlib import Path
import sys
import types
import unittest
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
              "v1", "a" * 64]
    return [{"longValue": v} if isinstance(v, int) else {"stringValue": v} for v in values]


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


if __name__ == "__main__":
    unittest.main()
