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

    def run_upgrade(self):
        upgrade.upgrade(self.sts, self.clusters, self.rds, self.secrets,
                        "123456789012", self.cluster, self.migrator)

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
        base = upgrade.reader.EXPECTED_COLUMNS - upgrade.reader.SOURCE_REPORT_COLUMNS
        before = {"connect": True, "schemaUsage": True, "selectedColumns": sorted(base)}
        after = {**before, "selectedColumns": sorted(upgrade.reader.EXPECTED_COLUMNS)}
        with patch.object(upgrade.reader, "inspect_role", side_effect=[before, after, after]), \
             patch.object(upgrade.reader, "validate_role"), \
             patch.object(upgrade, "verify_source_report") as probe:
            for _ in range(2):
                upgrade.upgrade(self.sts, self.clusters, self.rds, self.secrets,
                                "123456789012", self.cluster, self.migrator, secret)
        self.assertEqual([
            "GRANT SELECT (created_at) ON public.source_items TO job_agent_page_reader",
            "GRANT SELECT (source_item_id, policy_version, decision) ON public.vacancy_candidate_assessments TO job_agent_page_reader",
            "GRANT SELECT (location) ON public.vacancies TO job_agent_page_reader",
            "GRANT SELECT (employer_name) ON public.verified_source_registry TO job_agent_page_reader",
        ], [call.kwargs["sql"] for call in self.rds.execute_statement.call_args_list[1:-1]])
        probe.assert_called_with(self.rds, self.cluster, secret)
        self.assertEqual(2, probe.call_count)

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
