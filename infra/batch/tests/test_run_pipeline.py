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

    def test_report_accepts_one_combined_publication_after_bounded_polling(self):
        definition = {"containerDefinitions": [{"name": "job-agent", "logConfiguration": {
            "options": {"awslogs-stream-prefix": "batch", "awslogs-group": "/ecs/synthetic"}}}]}
        marker = ("Scheduled result: date=2026-11-05, status=PUBLISHED, "
                  "new_distinct=1, new_de=1, new_at=0, newly_relevant_distinct=0")
        with patch.object(runner, "SCHEDULED", True), patch.object(runner, "aws", side_effect=[
                {"events": []}, {"events": [{"message": "INFO " + marker}]}]) as aws, \
                patch.object(runner.time, "sleep") as sleep, \
                patch("builtins.print") as printed:
            runner.report("arn:aws:ecs:us-east-1:111111111111:task/reviewed/abc", definition, 0)
        self.assertEqual(2, aws.call_count)
        self.assertEqual(1, sleep.call_count)
        self.assertIn('"Scheduled result"', aws.call_args.args)
        printed.assert_called_once_with(marker, flush=True)

    def test_legacy_profile_markers_do_not_satisfy_combined_publication(self):
        definition = {"containerDefinitions": [{"name": "job-agent", "logConfiguration": {
            "options": {"awslogs-stream-prefix": "batch", "awslogs-group": "/ecs/synthetic"}}}]}
        with patch.object(runner, "SCHEDULED", True), patch.object(runner, "aws", return_value={
                "events": [{"message": "Scheduled report: date=2026-11-05, profile=alex-de, status=PUBLISHED"},
                           {"message": "Scheduled report: date=2026-11-05, profile=alex-at, status=PUBLISHED"}]}), \
                patch.object(runner.time, "sleep") as sleep:
            with self.assertRaisesRegex(RuntimeError, "Combined result publication log unavailable"):
                runner.report("arn:aws:ecs:us-east-1:111111111111:task/reviewed/abc", definition, 0)
        self.assertEqual(6, sleep.call_count)

    def test_missing_or_duplicate_combined_markers_fail_safely(self):
        definition = {"containerDefinitions": [{"name": "job-agent", "logConfiguration": {
            "options": {"awslogs-stream-prefix": "batch", "awslogs-group": "/ecs/synthetic"}}}]}
        marker = ("Scheduled result: date=2026-11-05, status=PUBLISHED, "
                  "new_distinct=0, new_de=0, new_at=0, newly_relevant_distinct=0")
        with patch.object(runner, "SCHEDULED", True), patch.object(runner, "aws", return_value={
                "events": [{"message": marker}, {"message": marker}]}):
            with self.assertRaisesRegex(RuntimeError, "Multiple combined result markers"):
                runner.report("arn:aws:ecs:us-east-1:111111111111:task/reviewed/abc", definition, 0)
        with patch.object(runner, "SCHEDULED", True), patch.object(runner, "aws", return_value={
                "events": [{"message": marker.replace("status=PUBLISHED", "status=PREPARED") }]}), \
                patch.object(runner.time, "sleep") as sleep:
            with self.assertRaisesRegex(RuntimeError, "Combined result publication log unavailable"):
                runner.report("arn:aws:ecs:us-east-1:111111111111:task/reviewed/abc", definition, 0)
        self.assertEqual(6, sleep.call_count)


if __name__ == "__main__":
    unittest.main()
