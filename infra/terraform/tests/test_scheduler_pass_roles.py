"""Guard the scheduled ECS task roles against a stale Scheduler PassRole policy."""

from __future__ import annotations

from pathlib import Path
import re
import unittest


TERRAFORM = Path(__file__).resolve().parents[1]
ROLE_ARN = r"aws_iam_role\.[a-z_]+\[0\]\.arn"


def resource(source: str, kind: str, name: str) -> str:
    match = re.search(
        rf'^resource "{re.escape(kind)}" "{re.escape(name)}" \{{(.*?)^\}}',
        source,
        re.MULTILINE | re.DOTALL,
    )
    if not match:
        raise AssertionError(f"Missing {kind}.{name} resource")
    return match.group(1)


def scheduled_roles(task_source: str) -> set[str]:
    task = resource(task_source, "aws_ecs_task_definition", "batch_pipeline")
    execution = re.search(
        rf"^\s*execution_role_arn\s*=\s*var\.enable_scheduled_batch\s*\?\s*({ROLE_ARN})\s*:",
        task,
        re.MULTILINE,
    )
    task_role = re.search(rf"^\s*task_role_arn\s*=\s*({ROLE_ARN})", task, re.MULTILINE)
    if not execution or not task_role:
        raise AssertionError("Scheduled task roles could not be identified")
    return {execution.group(1), task_role.group(1)}


def pass_roles(policy_source: str) -> set[str]:
    policy = resource(policy_source, "aws_iam_role_policy", "batch_scheduler")
    statement = re.search(
        r'Action\s*=\s*\["iam:PassRole"\]\s*'
        r'Resource\s*=\s*\[([^\n]+)\]\s*'
        r'Condition\s*=\s*\{\s*StringEquals\s*=\s*\{\s*'
        r'"iam:PassedToService"\s*=\s*"ecs-tasks.amazonaws.com"',
        policy,
    )
    if not statement:
        raise AssertionError("Scheduler PassRole statement or ECS service condition is missing")
    roles = re.findall(ROLE_ARN, statement.group(1))
    if len(roles) != 2 or len(set(roles)) != 2:
        raise AssertionError("Scheduler must pass exactly two distinct roles")
    return set(roles)


class SchedulerPassRolesTest(unittest.TestCase):
    def test_scheduler_policy_matches_scheduled_task_roles(self):
        task_source = (TERRAFORM / "sqs.tf").read_text()
        policy_source = (TERRAFORM / "scheduled_batch.tf").read_text()
        self.assertEqual(scheduled_roles(task_source), pass_roles(policy_source))

    def test_previous_evaluation_execution_role_mismatch_is_detected(self):
        task_source = (TERRAFORM / "sqs.tf").read_text()
        policy_source = (TERRAFORM / "scheduled_batch.tf").read_text()
        stale = policy_source.replace(
            "aws_iam_role.batch_execution[0].arn",
            "aws_iam_role.sqs_evaluation_execution[0].arn",
        )
        self.assertNotEqual(scheduled_roles(task_source), pass_roles(stale))

    def test_broadened_pass_role_service_is_rejected(self):
        policy_source = (TERRAFORM / "scheduled_batch.tf").read_text()
        broadened = policy_source.replace('"ecs-tasks.amazonaws.com"', '"scheduler.amazonaws.com"')
        with self.assertRaisesRegex(AssertionError, "ECS service condition"):
            pass_roles(broadened)


if __name__ == "__main__":
    unittest.main()
