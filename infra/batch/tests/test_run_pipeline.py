"""Offline checks for the bounded scheduled-report runner path."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "run_pipeline.py"
SPEC = importlib.util.spec_from_file_location("batch_runner", SCRIPT)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class ScheduledRunnerTest(unittest.TestCase):
    def test_launch_uses_reviewed_definition_and_explicit_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "progress.json"
            outputs = {
                "ecs_cluster_name": "reviewed-cluster",
                "batch_pipeline_task_definition_arn": "arn:aws:ecs:us-east-1:111111111111:task-definition/reviewed:4",
                "scheduled_batch_group": "reviewed-scheduled-batch",
                "task_subnet_ids": ["subnet-synthetic"],
                "task_security_group_id": "sg-synthetic",
                "database_endpoint": "synthetic.invalid",
            }
            calls = []

            def fake_aws(service, operation, *args):
                calls.append((service, operation, args))
                if operation == "describe-task-definition":
                    return {"taskDefinition": {"containerDefinitions": [{"name": "job-agent"}]}}
                if operation == "run-task":
                    return {"tasks": [{"taskArn": "arn:aws:ecs:us-east-1:111111111111:task/reviewed/abc"}]}
                if operation == "list-tasks":
                    return {"taskArns": []}
                raise AssertionError(operation)

            with patch.object(runner, "SCHEDULED", True), patch.object(runner, "STATE", state), \
                    patch.object(runner, "output", side_effect=outputs.__getitem__), \
                    patch.object(runner, "aws", side_effect=fake_aws), \
                    patch.object(runner, "wait_task", return_value=({"stoppedAt": "2026-11-05T18:02:00Z"}, 0)), \
                    patch.object(runner, "report") as report, patch.object(runner, "idle") as idle, \
                    patch.object(runner.sys, "argv", [str(SCRIPT), "--scheduled-report"]):
                runner.main()
                runner.main()
            launches = [call for call in calls if call[1] == "run-task"]
            self.assertEqual(1, len(launches))
            arguments = launches[0][2]
            self.assertEqual("reviewed-scheduled-batch", arguments[arguments.index("--group") + 1])
            override = json.loads(arguments[arguments.index("--overrides") + 1])
            self.assertEqual(["--mode=scheduled-batch"], override["containerOverrides"][0]["command"])
            self.assertTrue(state.exists())
            report.assert_called_with("arn:aws:ecs:us-east-1:111111111111:task/reviewed/abc",
                                      {"containerDefinitions": [{"name": "job-agent"}]}, 0)
            self.assertEqual(1, idle.call_count)

    def test_report_requires_both_profile_publications(self):
        definition = {"containerDefinitions": [{"name": "job-agent", "logConfiguration": {
            "options": {"awslogs-stream-prefix": "batch", "awslogs-group": "/ecs/synthetic"}}}]}
        with patch.object(runner, "SCHEDULED", True), patch.object(runner, "aws", return_value={
                "events": [{"message": "Scheduled report: date=2026-11-05, profile=alex-de, status=PUBLISHED, outcome=BACKLOG_REMAINS"},
                           {"message": "Scheduled report: date=2026-11-05, profile=alex-at, status=PUBLISHED, outcome=NO_NEW_VACANCIES"}]}):
            runner.report("arn:aws:ecs:us-east-1:111111111111:task/reviewed/abc", definition, 0)

    def test_one_published_profile_is_not_a_successful_scheduled_run(self):
        definition = {"containerDefinitions": [{"name": "job-agent", "logConfiguration": {
            "options": {"awslogs-stream-prefix": "batch", "awslogs-group": "/ecs/synthetic"}}}]}
        with patch.object(runner, "SCHEDULED", True), patch.object(runner, "aws", return_value={
                "events": [{"message": "Scheduled report: date=2026-11-05, profile=alex-de, status=PUBLISHED, outcome=BACKLOG_REMAINS"}]}), \
                patch.object(runner.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "summary log unavailable"):
                runner.report("arn:aws:ecs:us-east-1:111111111111:task/reviewed/abc", definition, 0)


if __name__ == "__main__":
    unittest.main()
