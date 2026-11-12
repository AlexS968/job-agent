"""Offline checks for the separate database reader bootstrap."""

import importlib.util
from pathlib import Path
import sys
import types
import unittest


class FakeClientError(Exception):
    def __init__(self, code):
        self.response = {"Error": {"Code": code}}


sys.modules.setdefault("boto3", types.SimpleNamespace(Session=None))
sys.modules.setdefault("botocore", types.ModuleType("botocore"))
sys.modules.setdefault("botocore.exceptions", types.SimpleNamespace(ClientError=FakeClientError))
spec = importlib.util.spec_from_file_location("reader_bootstrap", Path(__file__).with_name("prepare_reader.py"))
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)


class FakeRds:
    def __init__(self, present=False):
        self.present = present
        self.sql = []
        self.calls = []

    def execute_statement(self, **kwargs):
        self.calls.append(kwargs)
        self.sql.append(kwargs["sql"])
        return {"records": [[{"longValue": 1}]] if self.present else []}


class FakeSecrets:
    def __init__(self, present=False):
        self.present = present
        self.created = []

    def describe_secret(self, **_):
        if not self.present:
            raise FakeClientError("ResourceNotFoundException")

    def create_secret(self, **kwargs):
        self.created.append(kwargs)


class ReaderBootstrapTest(unittest.TestCase):
    def test_creates_select_only_role_with_scram_and_separate_secret(self):
        db, secret = FakeRds(), FakeSecrets()
        reader.prepare(db, secret, "cluster-test", "admin-secret-test", "migrator-secret-test")
        self.assertEqual(1, len(secret.created))
        self.assertIn("SCRAM-SHA-256$", db.sql[1])
        self.assertNotIn(secret.created[0]["SecretString"].split('"password": "')[1].split('"')[0],
                         " ".join(db.sql))
        self.assertTrue(all("GRANT SELECT" in statement or "GRANT USAGE" in statement or "GRANT CONNECT" in statement
                            for statement in db.sql[2:]))
        self.assertEqual("admin-secret-test", db.calls[2]["secretArn"])
        self.assertEqual("admin-secret-test", db.calls[3]["secretArn"])
        self.assertTrue(all(call["secretArn"] == "migrator-secret-test" for call in db.calls[4:]))
        self.assertNotIn("raw_payload", " ".join(db.sql))

    def test_existing_role_or_secret_stops_before_rotation(self):
        for role, secret_present in ((True, False), (False, True)):
            with self.subTest(role=role, secret=secret_present):
                db, secret = FakeRds(role), FakeSecrets(secret_present)
                with self.assertRaises(RuntimeError):
                    reader.prepare(db, secret, "cluster-test", "admin-secret-test", "migrator-secret-test")
                self.assertEqual([], secret.created)
                self.assertEqual(1, len(db.sql))


if __name__ == "__main__":
    unittest.main()
