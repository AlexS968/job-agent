"""Offline tests for the additive, resumable page grant upgrade."""

from pathlib import Path
import sys
import types
import unittest
from unittest.mock import Mock, patch

sys.modules.setdefault("boto3", types.SimpleNamespace(Session=Mock()))
sys.modules.setdefault("botocore", types.ModuleType("botocore"))
sys.modules.setdefault("botocore.exceptions", types.SimpleNamespace(ClientError=Exception))
sys.path.insert(0, str(Path(__file__).parent))
import upgrade_reader as upgrade


def reader_state(columns):
    return {
        "login": True, "super": False, "createdb": False, "createrole": False,
        "replication": False, "bypassrls": False, "memberships": 0,
        "connect": True, "schemaUsage": True, "databaseCreate": False, "schemaCreate": False,
        "otherSelectedColumns": 0, "writeColumns": 0, "writeTables": 0,
        "tableSelectAcl": 0, "selectedColumns": sorted(columns),
    }


class UpgradeReaderTest(unittest.TestCase):
    def setUp(self):
        self.sts = Mock()
        self.sts.get_caller_identity.return_value = {"Account": "123456789012"}
        self.clusters = Mock()
        self.cluster = "arn:aws:rds:us-east-1:123456789012:cluster:synthetic"
        self.clusters.describe_db_clusters.return_value = {"DBClusters": [{
            "DBClusterArn": self.cluster, "Status": "available", "HttpEndpointEnabled": True}]}
        self.rds = Mock()
        self.rds.execute_statement.return_value = {"records": [[{"stringValue": "job_agent_migrator"}]]}
        self.secrets = Mock()
        self.secrets.describe_secret.return_value = {}
        self.migrator = "arn:aws:secretsmanager:us-east-1:123456789012:secret:synthetic"

    def run_upgrade(self, report_reader=None):
        upgrade.upgrade(self.sts, self.clusters, self.rds, self.secrets,
                        "123456789012", self.cluster, self.migrator, report_reader)

    def test_partial_state_gets_only_additive_grants_and_can_replay(self):
        old = {"connect": True, "schemaUsage": True,
               "selectedColumns": list(upgrade.OLD_COLUMNS)}
        complete = {**old, "selectedColumns": list(upgrade.EXPECTED_COLUMNS)}
        with patch.object(upgrade.reader, "inspect_role", side_effect=[old, complete, complete]), \
             patch.object(upgrade.reader, "validate_role"):
            self.run_upgrade()
            self.run_upgrade()
        self.assertEqual(len(upgrade.ADDITIONAL_GRANTS) + 2, self.rds.execute_statement.call_count)
        for call in self.rds.execute_statement.call_args_list[1:-1]:
            self.assertEqual(self.migrator, call.kwargs["secretArn"])
            self.assertTrue(call.kwargs["sql"].startswith("GRANT SELECT"))

    def test_source_report_grants_only_approved_columns_and_probes_existing_reader(self):
        secret = "arn:aws:secretsmanager:us-east-1:123456789012:secret:synthetic-reader"
        self.secrets.describe_secret.return_value = {"ARN": secret}
        columns = (upgrade.reader.EXPECTED_COLUMNS - upgrade.reader.EVALUATION_INPUT_COLUMNS) | upgrade.FEEDBACK_READ_COLUMNS
        before = reader_state(columns - upgrade.reader.SOURCE_REPORT_COLUMNS)
        after = reader_state(columns)
        with patch.object(upgrade.reader, "inspect_role", side_effect=[before, after, after]), \
             patch.object(upgrade, "verify_source_report") as probe:
            for _ in range(2):
                self.run_upgrade(secret)
        self.assertEqual([
            "GRANT SELECT (created_at) ON public.source_items TO job_agent_page_reader",
            "GRANT SELECT (source_item_id, policy_version, decision) ON public.vacancy_candidate_assessments TO job_agent_page_reader",
            "GRANT SELECT (location) ON public.vacancies TO job_agent_page_reader",
            "GRANT SELECT (employer_name) ON public.verified_source_registry TO job_agent_page_reader",
        ], [call.kwargs["sql"] for call in self.rds.execute_statement.call_args_list[1:-1]])
        probe.assert_called_with(self.rds, self.cluster, secret)
        self.assertEqual(2, probe.call_count)

    def test_source_report_preflight_rejects_unapproved_feedback_privileges(self):
        secret = "arn:aws:secretsmanager:us-east-1:123456789012:secret:synthetic-reader"
        self.secrets.describe_secret.return_value = {"ARN": secret}
        columns = (upgrade.reader.EXPECTED_COLUMNS - upgrade.reader.EVALUATION_INPUT_COLUMNS) | upgrade.FEEDBACK_READ_COLUMNS
        cases = {
            "extra column": (reader_state(columns | {"review_feedback.request_id"}), "unexpected SELECT"),
            "partial projection": (reader_state(columns - {"review_feedback.recorded_at"}), "reviewed baseline"),
            "write access": ({**reader_state(columns), "writeColumns": 1}, "unexpected privileges"),
        }
        for label, (state, error) in cases.items():
            with self.subTest(label=label):
                self.rds.execute_statement.reset_mock()
                with patch.object(upgrade.reader, "inspect_role", return_value=state), \
                     patch.object(upgrade, "verify_source_report") as probe:
                    with self.assertRaisesRegex(RuntimeError, error):
                        self.run_upgrade(secret)
                self.assertEqual(1, self.rds.execute_statement.call_count)
                probe.assert_not_called()

    def test_evaluation_input_upgrade_is_column_only_and_keeps_feedback_projection(self):
        secret = "arn:aws:secretsmanager:us-east-1:123456789012:secret:synthetic-reader"
        self.secrets.describe_secret.return_value = {"ARN": secret}
        columns = upgrade.reader.EXPECTED_COLUMNS | upgrade.FEEDBACK_READ_COLUMNS
        before = reader_state(columns - upgrade.reader.EVALUATION_INPUT_COLUMNS)
        after = reader_state(columns)
        self.rds.execute_statement.side_effect = lambda **kwargs: {"records": [[{"stringValue":
            "job_agent_migrator" if kwargs["secretArn"] == self.migrator else upgrade.reader.ROLE}]]}
        with patch.object(upgrade.reader, "inspect_role", side_effect=[before, after, after]):
            for _ in range(2):
                upgrade.upgrade(self.sts, self.clusters, self.rds, self.secrets, "123456789012",
                                self.cluster, self.migrator, input_reader=secret)
        grants = [c.kwargs["sql"] for c in self.rds.execute_statement.call_args_list
                  if c.kwargs["sql"].startswith("GRANT")]
        self.assertEqual(1, len(grants))
        self.assertIn("ON public.vacancy_evaluation_inputs TO job_agent_page_reader", grants[0])
        self.assertNotIn("raw_payload", grants[0])
        self.assertNotIn("review_feedback", grants[0])
        self.assertNotIn("UPDATE", grants[0])

    def test_unexpected_privilege_state_stops_before_grants(self):
        invalid = {"connect": True, "schemaUsage": True,
                   "selectedColumns": list(upgrade.OLD_COLUMNS | {"source_items.raw_payload"})}
        with patch.object(upgrade.reader, "inspect_role", return_value=invalid), \
             patch.object(upgrade.reader, "validate_role"):
            with self.assertRaises(RuntimeError):
                self.run_upgrade()
        self.assertEqual(1, self.rds.execute_statement.call_count)  # Identity check only.

    def test_account_failure_stops_before_database_access(self):
        self.sts.get_caller_identity.return_value = {"Account": "000000000000"}
        with self.assertRaises(RuntimeError):
            self.run_upgrade()
        self.rds.execute_statement.assert_not_called()


if __name__ == "__main__":
    unittest.main()
