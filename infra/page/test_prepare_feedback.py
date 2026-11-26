"""Offline failure checks for the dedicated owner-feedback role bootstrap."""

from pathlib import Path
import sys
import types
import unittest
from unittest.mock import Mock, patch

sys.modules.setdefault("boto3", types.SimpleNamespace(Session=Mock()))
sys.modules.setdefault("botocore", types.ModuleType("botocore"))
sys.modules.setdefault("botocore.exceptions", types.SimpleNamespace(ClientError=Exception))
sys.path.insert(0, str(Path(__file__).parent))
import prepare_feedback as feedback


class FeedbackBootstrapTest(unittest.TestCase):
    def setUp(self):
        self.account = "123456789012"
        self.cluster = f"arn:aws:rds:us-east-1:{self.account}:cluster:synthetic"
        self.secret = f"arn:aws:secretsmanager:us-east-1:{self.account}:secret:synthetic"
        self.sts = Mock()
        self.sts.get_caller_identity.return_value = {"Account": self.account}
        self.clusters = Mock()
        self.clusters.describe_db_clusters.return_value = {"DBClusters": [{
            "DBClusterArn": self.cluster, "Status": "available", "HttpEndpointEnabled": True}]}
        self.rds = Mock()
        self.secrets = Mock()

        def execute(**kwargs):
            sql = kwargs["sql"]
            if sql == "SELECT current_user::text":
                value = "job_agent_migrator"
            elif "'public.vacancy_evaluation_inputs'::regclass" in sql:
                value = "job_agent_migrator"
            elif "pg_get_userbyid(relowner)" in sql:
                value = "job_agent_migrator:29"
            else:
                return {"records": []}
            return {"records": [[{"stringValue": value}]]}

        self.rds.execute_statement.side_effect = execute

    def run_prepare(self):
        feedback.prepare(self.sts, self.clusters, self.rds, self.secrets,
                         self.account, self.cluster, self.secret, self.secret)

    def test_wrong_account_stops_before_database_or_secret_access(self):
        self.sts.get_caller_identity.return_value = {"Account": "000000000000"}
        with self.assertRaisesRegex(RuntimeError, "Unexpected AWS account"):
            self.run_prepare()
        self.rds.execute_statement.assert_not_called()
        self.secrets.describe_secret.assert_not_called()

    def test_existing_secret_or_role_stops_before_any_grant(self):
        with patch.object(feedback, "secret_absent", side_effect=RuntimeError("existing secret")):
            with self.assertRaisesRegex(RuntimeError, "existing secret"):
                self.run_prepare()
        self.assertFalse(any(call.kwargs["sql"].startswith(("CREATE ROLE", "GRANT"))
                             for call in self.rds.execute_statement.call_args_list))
        self.rds.execute_statement.reset_mock()
        def existing_role(**kwargs):
            sql = kwargs["sql"]
            value = ("job_agent_migrator" if sql == "SELECT current_user::text" else
                     "job_agent_migrator" if "'public.vacancy_evaluation_inputs'::regclass" in sql else
                     "job_agent_migrator:29" if "pg_get_userbyid(relowner)" in sql else
                     "job_agent_feedback_writer")
            return {"records": [[{"stringValue": value}]]}
        self.rds.execute_statement.side_effect = existing_role
        with patch.object(feedback, "secret_absent"):
            with self.assertRaisesRegex(RuntimeError, "role exists"):
                self.run_prepare()
        self.assertFalse(any(call.kwargs["sql"].startswith(("CREATE ROLE", "GRANT"))
                             for call in self.rds.execute_statement.call_args_list))

    def test_reader_grant_failure_does_not_create_writer_secret(self):
        state = {"connect": True, "schemaUsage": True,
                 "selectedColumns": list(feedback.base.EXPECTED_COLUMNS)}

        def execute(**kwargs):
            sql = kwargs["sql"]
            if sql == "SELECT current_user::text":
                return {"records": [[{"stringValue": "job_agent_migrator"}]]}
            if "'public.vacancy_evaluation_inputs'::regclass" in sql:
                return {"records": [[{"stringValue": "job_agent_migrator"}]]}
            if "pg_get_userbyid(relowner)" in sql:
                return {"records": [[{"stringValue": "job_agent_migrator:29"}]]}
            if sql.startswith("GRANT SELECT"):
                raise RuntimeError("synthetic grant failure")
            return {"records": []}

        self.rds.execute_statement.side_effect = execute
        with patch.object(feedback, "secret_absent"), \
             patch.object(feedback.base, "inspect_role", return_value=state), \
             patch.object(feedback.base, "validate_role"):
            with self.assertRaisesRegex(RuntimeError, "synthetic grant failure"):
                self.run_prepare()
        self.secrets.create_secret.assert_not_called()
        self.assertFalse(any(call.kwargs["sql"].startswith("CREATE ROLE")
                             for call in self.rds.execute_statement.call_args_list))

    def test_fresh_setup_grants_exact_fingerprint_columns_and_verifies_writer(self):
        before = {"connect": True, "schemaUsage": True,
                  "selectedColumns": list(feedback.base.EXPECTED_COLUMNS)}
        after = {**before, "writeColumns": 0, "writeTables": 0, "selectedColumns": list(
            feedback.base.EXPECTED_COLUMNS | {f"review_feedback.{c}" for c in feedback.READ_COLUMNS})}
        original = self.rds.execute_statement.side_effect
        def execute(**kwargs):
            if "has_column_privilege" in kwargs["sql"]:
                return {"records": [[{"stringValue": "true"}]]}
            return original(**kwargs)
        self.rds.execute_statement.side_effect = execute
        self.secrets.create_secret.return_value = {"ARN": self.secret}
        self.secrets.describe_secret.return_value = {"ARN": self.secret}
        with patch.object(feedback, "secret_absent"), \
             patch.object(feedback.base, "inspect_role", side_effect=[before, after]), \
             patch.object(feedback.base, "validate_role"), \
             patch.object(feedback, "inspect_writer", return_value=writer_state()) as inspect:
            self.run_prepare()
        grants = [call.kwargs["sql"] for call in self.rds.execute_statement.call_args_list
                  if "ON public.vacancy_evaluation_inputs" in call.kwargs["sql"]]
        self.assertEqual([feedback.FINGERPRINT_GRANT], grants)
        inspect.assert_called_once_with(self.rds, self.cluster, self.secret)
        self.secrets.create_secret.assert_called_once()


def writer_state(columns=None):
    return {"login": True, "connect": True, "schemaUsage": True,
            **{key: 0 for key in ("super", "createdb", "createrole", "replication", "bypassrls", "memberships",
                                "databaseCreate", "schemaCreate", "otherSelectedColumns", "writeTables",
                                "tableSelectAcl", "forbiddenWriteColumns")},
            "writeColumns": len(feedback.WRITER_INSERT),
            "insertedColumns": [f"public.review_feedback.{c}" for c in feedback.WRITER_INSERT],
            "sequencePrivileges": ["public.review_feedback_id_seq:USAGE"],
            "selectedColumns": sorted(feedback.EXPECTED_SELECT if columns is None else columns)}


class FeedbackGrantUpgradeTest(unittest.TestCase):
    def setUp(self):
        FeedbackBootstrapTest.setUp(self)
        self.writer = f"arn:aws:secretsmanager:us-east-1:{self.account}:secret:synthetic-writer"
        self.secrets.describe_secret.return_value = {"ARN": self.writer}
        original = self.rds.execute_statement.side_effect
        self.rds.execute_statement.side_effect = lambda **kwargs: (
            {"records": [[{"stringValue": feedback.ROLE}]]}
            if kwargs["secretArn"] == self.writer and kwargs["sql"] == "SELECT current_user::text"
            else original(**kwargs))

    def run_upgrade(self):
        feedback.upgrade_inputs(self.sts, self.clusters, self.rds, self.secrets, self.account,
                               self.cluster, self.secret, self.writer)

    def test_exact_column_upgrade_and_completed_replay_do_not_change_credentials(self):
        old = writer_state(feedback.EXPECTED_SELECT - feedback.FINGERPRINT_SELECT)
        complete = writer_state()
        with patch.object(feedback, "inspect_writer", side_effect=[old, complete, complete, complete]):
            self.run_upgrade()
            self.run_upgrade()
        sql = [call.kwargs["sql"] for call in self.rds.execute_statement.call_args_list]
        self.assertEqual([feedback.FINGERPRINT_GRANT], [s for s in sql if s.startswith("GRANT")])
        self.assertEqual("GRANT SELECT (vacancy_id, source_item_id, input_fingerprint, expires_at) "
                         "ON public.vacancy_evaluation_inputs TO job_agent_feedback_writer", feedback.FINGERPRINT_GRANT)
        self.assertEqual(2, sum(s.startswith("SELECT vacancy_id, source_item_id, input_fingerprint, expires_at") for s in sql))
        self.secrets.get_secret_value.assert_not_called()
        self.secrets.create_secret.assert_not_called()
        self.assertFalse(any(s.startswith(("CREATE ROLE", "ALTER ROLE", "REVOKE")) for s in sql))

    def test_unexpected_privileges_are_rejected_before_any_grant(self):
        old = writer_state(feedback.EXPECTED_SELECT - feedback.FINGERPRINT_SELECT)
        cases = [
            {**old, "selectedColumns": old["selectedColumns"] + ["vacancy_evaluation_inputs.original_url"]},
            {**old, "selectedColumns": old["selectedColumns"] + ["source_items.raw_payload"]},
            {**old, "tableSelectAcl": 1}, {**old, "forbiddenWriteColumns": 1},
            {**old, "writeTables": 1}, {**old, "memberships": 1},
            {**old, "insertedColumns": old["insertedColumns"] + ["public.source_items.raw_payload"]},
            {**old, "sequencePrivileges": old["sequencePrivileges"] + ["public.review_feedback_id_seq:UPDATE"]},
        ]
        for state in cases:
            with self.subTest(state=state):
                self.rds.execute_statement.reset_mock()
                with patch.object(feedback, "inspect_writer", return_value=state):
                    with self.assertRaises(RuntimeError):
                        self.run_upgrade()
                self.assertFalse(any(c.kwargs["sql"].startswith("GRANT") for c in self.rds.execute_statement.call_args_list))

    def test_incomplete_post_grant_verification_stops_before_writer_probe(self):
        old = writer_state(feedback.EXPECTED_SELECT - feedback.FINGERPRINT_SELECT)
        with patch.object(feedback, "inspect_writer", side_effect=[old, old]):
            with self.assertRaisesRegex(RuntimeError, "SELECT grants"):
                self.run_upgrade()
        self.assertFalse(any(c.kwargs["secretArn"] == self.writer for c in self.rds.execute_statement.call_args_list))
        self.secrets.create_secret.assert_not_called()

    def test_grant_failure_stops_without_retry(self):
        old = writer_state(feedback.EXPECTED_SELECT - feedback.FINGERPRINT_SELECT)
        original = self.rds.execute_statement.side_effect
        def fail_grant(**kwargs):
            if kwargs["sql"] == feedback.FINGERPRINT_GRANT:
                raise RuntimeError("synthetic grant failure")
            return original(**kwargs)
        self.rds.execute_statement.side_effect = fail_grant
        with patch.object(feedback, "inspect_writer", return_value=old):
            with self.assertRaisesRegex(RuntimeError, "synthetic grant failure"):
                self.run_upgrade()
        self.assertEqual(1, sum(c.kwargs["sql"] == feedback.FINGERPRINT_GRANT
                                for c in self.rds.execute_statement.call_args_list))

    def test_wrong_account_or_secret_identity_stops_before_role_mutation(self):
        self.sts.get_caller_identity.return_value = {"Account": "000000000000"}
        with self.assertRaisesRegex(RuntimeError, "Unexpected AWS account"):
            self.run_upgrade()
        self.rds.execute_statement.assert_not_called()
        self.sts.get_caller_identity.return_value = {"Account": self.account}
        self.secrets.describe_secret.return_value = {"ARN": self.secret}
        with self.assertRaisesRegex(RuntimeError, "secret metadata"):
            self.run_upgrade()
        self.rds.execute_statement.assert_not_called()


if __name__ == "__main__":
    unittest.main()
