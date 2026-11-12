"""Offline state-machine checks; all identities and credentials are synthetic."""

from datetime import datetime, timedelta, timezone
import importlib.util
import json
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch


class FakeClientError(Exception):
    def __init__(self, code):
        self.response = {"Error": {"Code": code}}


sys.modules.setdefault("boto3", types.SimpleNamespace(Session=None))
sys.modules.setdefault("botocore", types.ModuleType("botocore"))
sys.modules.setdefault("botocore.exceptions", types.SimpleNamespace(ClientError=FakeClientError))
spec = importlib.util.spec_from_file_location("reader_bootstrap", Path(__file__).with_name("prepare_reader.py"))
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)

ACCOUNT = "123456789012"
CLUSTER = f"arn:aws:rds:us-east-1:{ACCOUNT}:cluster:synthetic-test"
ADMIN = f"arn:aws:secretsmanager:us-east-1:{ACCOUNT}:secret:admin-test"
MIGRATOR = f"arn:aws:secretsmanager:us-east-1:{ACCOUNT}:secret:migrator-test"


def role_state(columns=(), connect=True, usage=True, **changes):
    state = {"login": True, "super": False, "createdb": False, "createrole": False,
             "replication": False, "bypassrls": False, "memberships": 0,
             "databaseCreate": False, "schemaCreate": False, "otherSelectedColumns": 0,
             "writeColumns": 0, "writeTables": 0, "tableSelectAcl": 0,
             "connect": connect, "schemaUsage": usage, "selectedColumns": list(columns)}
    state.update(changes)
    return state


class FakeSts:
    def __init__(self, account=ACCOUNT):
        self.account = account

    def get_caller_identity(self):
        return {"Account": self.account}


class FakeClusters:
    def __init__(self, enabled=True):
        self.enabled = enabled

    def describe_db_clusters(self, **_):
        return {"DBClusters": [{"DBClusterArn": CLUSTER, "Engine": "aurora-postgresql",
                                "EngineVersion": "17.11",
                                "DatabaseName": "job_agent", "Status": "available",
                                "HttpEndpointEnabled": self.enabled}]}


class FakeRds:
    def __init__(self, state=None, fail_grant=False, fail_auth=False, owners=None):
        self.state = state
        self.fail_grant = fail_grant
        self.fail_auth = fail_auth
        self.owners = owners if owners is not None else sorted(reader.TABLE_COLUMNS)
        self.calls = []

    def execute_statement(self, **kwargs):
        self.calls.append(kwargs)
        sql = kwargs["sql"]
        if sql == reader.ROLE_STATE_SQL:
            return {"records": [] if self.state is None else [[{"stringValue": json.dumps(self.state)}]]}
        if sql == reader.OWNER_SQL:
            return {"records": [[{"stringValue": json.dumps(self.owners)}]]}
        if sql == "SELECT current_user::text":
            if self.fail_auth:
                self.fail_auth = False
                raise RuntimeError("synthetic Data API authentication failure")
            return {"records": [[{"stringValue": "job_agent_migrator"}]]}
        if sql.startswith("CREATE ROLE"):
            self.state = role_state(columns=(), connect=False, usage=False)
        if sql.startswith("GRANT CONNECT"):
            self.state["connect"] = True
        if sql.startswith("GRANT USAGE"):
            self.state["schemaUsage"] = True
        if sql.startswith("GRANT SELECT"):
            if self.fail_grant:
                self.fail_grant = False
                raise RuntimeError("synthetic grant failure")
            table = sql.split(" ON public.")[1].split()[0]
            self.state["selectedColumns"] = sorted(set(self.state["selectedColumns"]) |
                                                    {f"{table}.{column}" for column in reader.TABLE_COLUMNS[table]})
        return {"records": []}


class FakeSecrets:
    def __init__(self, fields=None, reader_present=False, uncertain_create=False, fail_put=False):
        self.fields = fields if fields is not None else {"password": "synthetic-password", "extra": {"keep": True}}
        self.reader_present = reader_present
        self.uncertain_create = uncertain_create
        self.fail_put = fail_put
        self.version = 1
        self.puts = []
        self.created = []

    def describe_secret(self, **_):
        if not self.reader_present:
            raise FakeClientError("ResourceNotFoundException")
        return {"ARN": "reader-test", "VersionIdsToStages": {"synthetic-version": ["AWSCURRENT"]}}

    def get_secret_value(self, **kwargs):
        return {"ARN": kwargs["SecretId"], "VersionId": str(self.version),
                "SecretString": json.dumps(self.fields)}

    def put_secret_value(self, **kwargs):
        if self.fail_put:
            raise RuntimeError("synthetic version update failure")
        self.puts.append(kwargs)
        self.fields = json.loads(kwargs["SecretString"])
        self.version += 1

    def create_secret(self, **kwargs):
        self.created.append(kwargs)
        self.reader_present = True
        if self.uncertain_create:
            raise RuntimeError("synthetic lost acknowledgement")
        return {"ARN": "reader-test"}


def prepare(db, secrets_client, sts=None, cluster=None):
    return reader.prepare(sts or FakeSts(), cluster or FakeClusters(), db, secrets_client,
                          ACCOUNT, CLUSTER, ADMIN, MIGRATOR)


class ReaderBootstrapTest(unittest.TestCase):
    def test_fresh_role_and_secret_preserve_migrator_password_and_fields(self):
        db, client = FakeRds(), FakeSecrets()
        prepare(db, client)
        self.assertEqual({"password": "synthetic-password", "extra": {"keep": True},
                          "username": "job_agent_migrator"}, client.fields)
        self.assertEqual(1, len(client.puts))
        self.assertEqual(1, len(client.created))
        self.assertEqual(reader.EXPECTED_COLUMNS, set(db.state["selectedColumns"]))
        self.assertIn("SCRAM-SHA-256$", next(c["sql"] for c in db.calls if c["sql"].startswith("CREATE ROLE")))
        self.assertNotIn("synthetic-password", " ".join(c["sql"] for c in db.calls))
        self.assertTrue(all(c["secretArn"] == MIGRATOR for c in db.calls if c["sql"].startswith("GRANT SELECT")))

    def test_observed_partial_role_rotates_then_grants(self):
        db = FakeRds(role_state())
        client = FakeSecrets()
        prepare(db, client)
        self.assertEqual(1, len([c for c in db.calls if c["sql"].startswith("ALTER ROLE")]))
        self.assertEqual(1, len(client.created))
        self.assertEqual(reader.EXPECTED_COLUMNS, set(db.state["selectedColumns"]))

    def test_grant_failure_keeps_reader_secret_absent_and_reviewed_retry_rotates(self):
        db, client = FakeRds(role_state(), fail_grant=True), FakeSecrets()
        with self.assertRaises(RuntimeError):
            prepare(db, client)
        self.assertEqual([], client.created)
        self.assertEqual("job_agent_migrator", client.fields["username"])
        prepare(db, client)
        self.assertEqual(2, len([c for c in db.calls if c["sql"].startswith("ALTER ROLE")]))
        self.assertEqual(1, len(client.created))

    def test_secret_update_then_auth_failure_is_resumable_without_second_version(self):
        db, client = FakeRds(role_state(), fail_auth=True), FakeSecrets()
        with self.assertRaises(RuntimeError):
            prepare(db, client)
        self.assertEqual([], client.created)
        self.assertEqual(1, len(client.puts))
        prepare(db, client)
        self.assertEqual(1, len(client.puts))
        self.assertEqual(1, len(client.created))

    def test_secret_version_failure_prevents_role_mutation(self):
        db, client = FakeRds(role_state()), FakeSecrets(fail_put=True)
        with self.assertRaises(RuntimeError):
            prepare(db, client)
        self.assertFalse(any(c["sql"].startswith(("ALTER ROLE", "GRANT")) for c in db.calls))
        self.assertEqual([], client.created)

    def test_lost_reader_secret_acknowledgement_blocks_blind_retry(self):
        db, client = FakeRds(role_state()), FakeSecrets(uncertain_create=True)
        with self.assertRaises(RuntimeError):
            prepare(db, client)
        calls = len(db.calls)
        with self.assertRaises(RuntimeError):
            prepare(db, client)
        self.assertFalse(any(c["sql"].startswith(("ALTER ROLE", "GRANT")) for c in db.calls[calls:]))
        self.assertEqual(1, len(client.created))

    def test_already_correct_migrator_secret_is_not_rewritten(self):
        client = FakeSecrets(fields={"password": "synthetic", "username": "job_agent_migrator",
                                     "other": ["preserved"]})
        prepare(FakeRds(), client)
        self.assertEqual([], client.puts)
        self.assertEqual(["preserved"], client.fields["other"])

    def test_unexpected_state_fails_before_mutation(self):
        cases = [(FakeSts("000000000000"), FakeClusters(), FakeRds(), FakeSecrets()),
                 (FakeSts(), FakeClusters(False), FakeRds(), FakeSecrets()),
                 (FakeSts(), FakeClusters(), FakeRds(role_state(writeTables=1)), FakeSecrets()),
                 (FakeSts(), FakeClusters(), FakeRds(role_state(columns=["source_items.raw_payload"])), FakeSecrets()),
                 (FakeSts(), FakeClusters(), FakeRds(owners=["source_items"]), FakeSecrets()),
                 (FakeSts(), FakeClusters(), FakeRds(), FakeSecrets(reader_present=True)),
                 (FakeSts(), FakeClusters(), FakeRds(), FakeSecrets(fields={"password": "synthetic", "username": "wrong"}))]
        for index, (sts, cluster, db, client) in enumerate(cases):
            with self.subTest(case=index):
                with self.assertRaises(RuntimeError):
                    prepare(db, client, sts, cluster)
                self.assertFalse(any(c["sql"].startswith(("CREATE ROLE", "ALTER ROLE", "GRANT"))
                                     for c in db.calls))
                self.assertEqual([], client.puts)

    def test_credential_handoff_fail_closed_without_leaking_output(self):
        class Result:
            returncode = 1
            stdout = "synthetic-key"
        with self.assertRaisesRegex(RuntimeError, "handoff failed") as caught:
            reader.credential_session(run=lambda *_, **__: Result())
        self.assertNotIn("synthetic-key", str(caught.exception))

    def test_credential_handoff_uses_short_lived_values_only_in_memory(self):
        expiry = (datetime.now(timezone.utc) + timedelta(minutes=20)).isoformat()
        value = json.dumps({"Version": 1, "AccessKeyId": "synthetic-id",
                            "SecretAccessKey": "synthetic-key", "SessionToken": "synthetic-token",
                            "Expiration": expiry})
        class Result:
            returncode = 0
            stdout = value
        with patch.object(reader.boto3, "Session", return_value="session", create=True) as session:
            self.assertEqual("session", reader.credential_session(run=lambda *_, **__: Result()))
        self.assertEqual("synthetic-key", session.call_args.kwargs["aws_secret_access_key"])

    def test_credential_handoff_rejects_near_expiry(self):
        expiry = (datetime.now(timezone.utc) + timedelta(minutes=2)).isoformat()
        value = json.dumps({"Version": 1, "AccessKeyId": "synthetic-id",
                            "SecretAccessKey": "synthetic-key", "SessionToken": "synthetic-token",
                            "Expiration": expiry})
        class Result:
            returncode = 0
            stdout = value
        with self.assertRaisesRegex(RuntimeError, "before mutation"):
            reader.credential_session(run=lambda *_, **__: Result())


if __name__ == "__main__":
    unittest.main()
