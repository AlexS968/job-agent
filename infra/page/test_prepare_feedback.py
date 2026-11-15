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
            elif "pg_get_userbyid(relowner)" in sql:
                value = "job_agent_migrator:20"
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
                     "job_agent_migrator:20" if "pg_get_userbyid(relowner)" in sql else
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
            if "pg_get_userbyid(relowner)" in sql:
                return {"records": [[{"stringValue": "job_agent_migrator:20"}]]}
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


if __name__ == "__main__":
    unittest.main()
