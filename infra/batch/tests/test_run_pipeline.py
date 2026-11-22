"""Offline checks for the bounded scheduled-collection runner path."""

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
                    patch.object(runner, "show_result") as report, patch.object(runner, "idle") as idle, \
                    patch.object(runner.sys, "argv", [str(SCRIPT), "--scheduled-collection"]):
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

    def test_accepts_one_collection_completion_after_bounded_polling(self):
        definition = {"containerDefinitions": [{"name": "job-agent", "logConfiguration": {
            "options": {"awslogs-stream-prefix": "batch", "awslogs-group": "/ecs/synthetic"}}}]}
        marker = "Scheduled collection: date=2026-11-05, status=COMPLETE, de_evaluated=true, at_evaluated=true"
        with patch.object(runner, "SCHEDULED", True), patch.object(runner, "aws", side_effect=[
                {"events": []}, {"events": []}, {"events": [{"message": "INFO " + marker}]}]) as aws, \
                patch.object(runner.time, "sleep") as sleep, \
                patch("builtins.print") as printed:
            runner.show_result("arn:aws:ecs:us-east-1:111111111111:task/reviewed/abc", definition, 0)
        self.assertEqual(3, aws.call_count)
        self.assertEqual(1, sleep.call_count)
        self.assertIn('"Scheduled collection"', aws.call_args.args)
        printed.assert_called_once_with(marker, flush=True)

    def test_legacy_email_markers_do_not_satisfy_collection_completion(self):
        definition = {"containerDefinitions": [{"name": "job-agent", "logConfiguration": {
            "options": {"awslogs-stream-prefix": "batch", "awslogs-group": "/ecs/synthetic"}}}]}
        with patch.object(runner, "SCHEDULED", True), patch.object(runner, "aws", side_effect=lambda *args: {"events": []} if "already completed" in args[-1] else {
                "events": [{"message": "Scheduled report: date=2026-11-05, profile=alex-de, status=PUBLISHED"},
                           {"message": "Scheduled report: date=2026-11-05, profile=alex-at, status=PUBLISHED"}]}), \
                patch.object(runner.time, "sleep") as sleep:
            with self.assertRaisesRegex(RuntimeError, "Scheduled collection completion log unavailable"):
                runner.show_result("arn:aws:ecs:us-east-1:111111111111:task/reviewed/abc", definition, 0)
        self.assertEqual(6, sleep.call_count)

    def test_missing_or_duplicate_collection_markers_fail_safely(self):
        definition = {"containerDefinitions": [{"name": "job-agent", "logConfiguration": {
            "options": {"awslogs-stream-prefix": "batch", "awslogs-group": "/ecs/synthetic"}}}]}
        marker = "Scheduled collection: date=2026-11-05, status=COMPLETE, de_evaluated=true, at_evaluated=true"
        with patch.object(runner, "SCHEDULED", True), patch.object(runner, "aws", return_value={
                "events": [{"message": marker}, {"message": marker}]}):
            with self.assertRaisesRegex(RuntimeError, "Multiple collection completion markers"):
                runner.show_result("arn:aws:ecs:us-east-1:111111111111:task/reviewed/abc", definition, 0)
        with patch.object(runner, "SCHEDULED", True), patch.object(runner, "aws", side_effect=lambda *args: {"events": []} if "already completed" in args[-1] else {
                "events": [{"message": marker.replace("status=COMPLETE", "status=FAILED") }]}), \
                patch.object(runner.time, "sleep") as sleep:
            with self.assertRaisesRegex(RuntimeError, "Scheduled collection completion log unavailable"):
                runner.show_result("arn:aws:ecs:us-east-1:111111111111:task/reviewed/abc", definition, 0)
        self.assertEqual(6, sleep.call_count)


    def test_same_day_skip_is_visible_without_claiming_collection(self):
        definition = {"containerDefinitions": [{"name": "job-agent", "logConfiguration": {
            "options": {"awslogs-stream-prefix": "batch", "awslogs-group": "/ecs/synthetic"}}}]}
        with patch.object(runner, "SCHEDULED", True), patch.object(runner, "aws", side_effect=[
                {"events": []}, {"events": [{"message": "already completed; no processing repeated"}]}]):
            self.assertFalse(runner.show_result("arn:aws:ecs:us-east-1:111111111111:task/reviewed/abc", definition, 0))


if __name__ == "__main__":
    unittest.main()
