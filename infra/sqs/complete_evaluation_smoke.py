#!/usr/bin/env python3
"""Resume one reviewed SQS evaluation smoke; run only after cloud publication."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[2]
EVENT_ID = "f2f80674-67d8-4ca8-8641-cbc715d54265"
ENVELOPE = Path("/tmp/job-agent-evaluation-message.json")
PROGRESS = Path("/tmp/job-agent-evaluation-smoke-progress.json")
REGION = "us-east-1"
PROFILE = "job-agent"
TASK_LIMIT = timedelta(minutes=15)
IDLE_LIMIT = timedelta(minutes=30)
SAFE_LOG = re.compile(r"SQS evaluation (?:event committed|invocation|status):")


class SmokeError(Exception):
    pass


def command(args, label):
    try:
        result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True,
                                timeout=75, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise SmokeError(f"{label} could not complete; inspect local configuration") from error
    if result.returncode:
        raise SmokeError(f"{label} failed (exit {result.returncode}); no further smoke action was taken")
    return result.stdout.strip()


def aws(service, operation, *args):
    raw = command(["aws", "--profile", PROFILE, "--region", REGION,
                   "--no-cli-pager", "--output", "json", service, operation, *args],
                  f"AWS {service} {operation}")
    try:
        return json.loads(raw)
    except json.JSONDecodeError as error:
        raise SmokeError(f"AWS {service} {operation} returned invalid JSON") from error


def output(name):
    raw = command(["terraform", "-chdir=infra/terraform", "output", "-json", name],
                  f"Terraform output {name}")
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as error:
        raise SmokeError(f"Terraform output {name} is invalid") from error
    if not value:
        raise SmokeError(f"Terraform output {name} is missing")
    return value


def timestamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def stamp():
    return datetime.now(timezone.utc).isoformat()


def load_envelope():
    try:
        messages = json.loads(ENVELOPE.read_text())["Messages"]
        if len(messages) != 1:
            raise ValueError("Expected exactly one saved message")
        body = messages[0]["Body"]
        envelope = json.loads(body)
        if envelope["eventId"] != EVENT_ID or envelope["schemaVersion"] != 1:
            raise ValueError("Saved event identity differs from the reviewed event")
        if len(body.encode("utf-8")) > 4096:
            raise ValueError("Saved event body exceeds the consumer limit")
        return body, envelope
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise SmokeError("Saved envelope is missing or invalid; no AWS task was started") from error


def save(progress):
    PROGRESS.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".job-agent-sqs-smoke-", dir=PROGRESS.parent)
    try:
        with os.fdopen(fd, "w") as file:
            json.dump(progress, file, sort_keys=True, indent=2)
            file.write("\n")
            file.flush()
            os.fsync(file.fileno())
        os.replace(temporary, PROGRESS)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def load_progress(digest):
    if PROGRESS.is_symlink():
        raise SmokeError("Progress path must not be a symlink")
    if PROGRESS.exists():
        details = PROGRESS.stat()
        if details.st_uid != os.getuid() or details.st_mode & 0o077:
            raise SmokeError("Progress file must be owned by this user and private (0600)")
        data = json.loads(PROGRESS.read_text())
        if data.get("event_id") != EVENT_ID or data.get("body_sha256") != digest:
            raise SmokeError("Progress file belongs to a different envelope; review it manually")
        return data
    data = {"event_id": EVENT_ID, "body_sha256": digest, "tasks": {}}
    save(data)
    return data


def task_mode(task):
    overrides = task.get("overrides", {}).get("containerOverrides", [])
    commands = [item.get("command") for item in overrides if item.get("name") == "job-agent"]
    if not commands or commands == [None]:
        return "evaluate"
    if len(commands) != 1 or not commands[0]:
        return "other"
    first = commands[0][0]
    if first == "--mode=sqs-evaluate":
        return "evaluate"
    if first == "--mode=sqs-evaluation-status" and commands[0] == [first, f"--event-id={EVENT_ID}"]:
        return "status"
    return "other"


def describe(cluster, arns):
    if not arns:
        return []
    return aws("ecs", "describe-tasks", "--cluster", cluster, "--tasks", *arns)["tasks"]


def candidates(cluster, definition, mode, after):
    family = definition.rsplit("/", 1)[-1].split(":", 1)[0]
    arns = set()
    for desired in ("PENDING", "RUNNING", "STOPPED"):
        found = aws("ecs", "list-tasks", "--cluster", cluster, "--family", family,
                    "--desired-status", desired)
        arns.update(found.get("taskArns", []))
    tasks = []
    arn_list = sorted(arns)
    for offset in range(0, len(arn_list), 100):
        tasks.extend(describe(cluster, arn_list[offset:offset + 100]))
    return [task for task in tasks if task.get("taskDefinitionArn") == definition
            and task_mode(task) == mode and timestamp(task["createdAt"]) >= after]


def relevant_logs(task, log_group, prefix):
    stream = f"{prefix}/job-agent/{task['taskArn'].rsplit('/', 1)[-1]}"
    found = aws("logs", "filter-log-events", "--log-group-name", log_group,
                "--log-stream-names", stream, "--filter-pattern", '"SQS evaluation"')
    lines = [event["message"].strip() for event in found.get("events", [])]
    return [line for line in lines if SAFE_LOG.search(line)]


def wait_task(cluster, arn, log_group, prefix):
    last = None
    last_progress = 0.0
    while True:
        tasks = describe(cluster, [arn])
        if len(tasks) != 1:
            raise SmokeError("Tracked ECS task is unavailable; inspect its ARN before continuing")
        task = tasks[0]
        status = task.get("lastStatus")
        if status != last or time.monotonic() - last_progress >= 60:
            print(f"Task {arn.rsplit('/', 1)[-1]}: {status}", flush=True)
            last = status
            last_progress = time.monotonic()
        if status == "STOPPED":
            container = next((item for item in task.get("containers", [])
                              if item.get("name") == "job-agent"), None)
            for attempt in range(7):
                lines = relevant_logs(task, log_group, prefix)
                if lines:
                    for line in lines:
                        print(line, flush=True)
                    if container is None or container.get("exitCode") != 0:
                        raise SmokeError(f"Tracked task stopped with exit code {None if container is None else container.get('exitCode')}")
                    return task, lines
                if attempt < 6:
                    time.sleep(10)
            if container is None or container.get("exitCode") != 0:
                raise SmokeError(f"Tracked task stopped with exit code {None if container is None else container.get('exitCode')}; relevant logs unavailable")
            raise SmokeError("Task exited 0 but its relevant log line is unavailable")
        created = timestamp(task["createdAt"])
        if datetime.now(timezone.utc) >= created + TASK_LIMIT:
            aws("ecs", "stop-task", "--cluster", cluster, "--task", arn,
                "--reason", "Bounded evaluation smoke timeout")
            raise SmokeError("Task exceeded 15 minutes and a stop was requested; confirm it stopped")
        time.sleep(15)


def task_for_stage(progress, stage, cluster, definition, mode, after, network, log_group, prefix):
    record = progress["tasks"].get(stage)
    if record and record.get("done"):
        task, lines = wait_task(cluster, record["arn"], log_group, prefix)
        if task.get("taskDefinitionArn") != definition or task_mode(task) != mode:
            raise SmokeError(f"Saved {stage} task no longer matches the reviewed definition/mode")
        return lines, record["stopped_at"]
    if record and record.get("arn"):
        arn = record["arn"]
        tasks = describe(cluster, [arn])
        if len(tasks) != 1 or tasks[0].get("taskDefinitionArn") != definition or task_mode(tasks[0]) != mode:
            raise SmokeError(f"Saved {stage} task no longer matches the reviewed definition/mode")
    else:
        discovery_after = timestamp(record["intent_at"]) if record else after
        matches = candidates(cluster, definition, mode, discovery_after)
        if stage == "first_evaluation" and not record:
            identified = [task for task in matches if task.get("lastStatus") != "STOPPED" or any(
                f"event_id={EVENT_ID}, outcome=" in line
                for line in relevant_logs(task, log_group, prefix))]
            if matches and not identified:
                raise SmokeError("Prior evaluation tasks exist without identifying event logs; review before another launch")
            matches = identified
        if len(matches) > 1:
            raise SmokeError(f"Multiple possible {stage} tasks exist; inspect task metadata before continuing")
        if matches:
            arn = matches[0]["taskArn"]
            print(f"Resuming existing {stage} task {arn.rsplit('/', 1)[-1]}", flush=True)
        elif record:
            raise SmokeError(f"{stage} launch intent exists but no task is identifiable; do not launch again blindly")
        elif stage == "first_evaluation" and datetime.now(timezone.utc) - after > timedelta(hours=1):
            raise SmokeError("Existing task history may have expired; review the event before launching another task")
        else:
            record = {"intent_at": stamp()}
            progress["tasks"][stage] = record
            save(progress)
            args = ["--cluster", cluster, "--launch-type", "FARGATE", "--task-definition", definition,
                    "--count", "1", "--network-configuration", json.dumps(network)]
            if mode == "status":
                args += ["--overrides", json.dumps({"containerOverrides": [{"name": "job-agent",
                           "command": ["--mode=sqs-evaluation-status", f"--event-id={EVENT_ID}"]}]})]
            launched = aws("ecs", "run-task", *args)
            if launched.get("failures") or len(launched.get("tasks", [])) != 1:
                raise SmokeError(f"{stage} launch failed; inspect ECS before rerunning")
            arn = launched["tasks"][0]["taskArn"]
            print(f"Launched {stage} task {arn.rsplit('/', 1)[-1]}", flush=True)
        progress["tasks"][stage] = {"arn": arn, "intent_at": record.get("intent_at", stamp()) if record else stamp()}
        save(progress)
    task, lines = wait_task(cluster, arn, log_group, prefix)
    stopped = task.get("stoppedAt", stamp())
    progress["tasks"][stage].update({"done": True, "stopped_at": stopped})
    save(progress)
    return lines, stopped


def require_invocation(lines, duplicate):
    expected = "DUPLICATE" if duplicate else None
    if not any(f"event_id={EVENT_ID}, outcome=" in line and
               (expected is None or f"outcome={expected}" in line) for line in lines):
        raise SmokeError("Evaluation task logs do not identify the reviewed event")
    match = next((re.search(r"SQS evaluation invocation: evaluated=(\d+), duplicates=(\d+), "
                            r"stale=(\d+), failed=(\d+)", line) for line in lines
                  if "SQS evaluation invocation:" in line), None)
    if match is None:
        raise SmokeError("Evaluation counters are unavailable")
    evaluated, duplicates, stale, failed = map(int, match.groups())
    if stale or failed or (duplicate and (evaluated, duplicates) != (0, 1)) or (
            not duplicate and (evaluated, duplicates) not in ((1, 0), (0, 1))):
        raise SmokeError("Evaluation counters do not match the bounded smoke")


def require_status(lines, previous=None):
    pattern = re.compile(rf"SQS evaluation status: event_id={EVENT_ID}, outcome=EVALUATED, "
                         r"evaluation_id=(\d+),")
    match = next((pattern.search(line) for line in lines if pattern.search(line)), None)
    if match is None:
        raise SmokeError("Read-only status did not confirm a persisted EVALUATED result")
    evaluation_id = int(match.group(1))
    if previous is not None and evaluation_id != previous:
        raise SmokeError("Duplicate delivery changed the persisted evaluation identity")
    return evaluation_id


def resend_once(progress, queue_url, body):
    if progress.get("resend_message_id"):
        print("Exact envelope was already resent; continuing", flush=True)
        return
    if progress.get("resend_intent_at"):
        raise SmokeError("Resend outcome is ambiguous; inspect the queue before any further send")
    counts = aws("sqs", "get-queue-attributes", "--queue-url", queue_url,
                 "--attribute-names", "ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible")
    attributes = counts.get("Attributes", {})
    if any(int(attributes.get(name, "-1")) != 0 for name in
           ("ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible")):
        raise SmokeError("Source queue is not visibly empty; do not add a duplicate envelope")
    progress["resend_intent_at"] = stamp()
    save(progress)
    sent = aws("sqs", "send-message", "--queue-url", queue_url, "--message-body", body)
    if not sent.get("MessageId"):
        raise SmokeError("SQS resend returned no message ID; outcome requires review")
    progress["resend_message_id"] = sent["MessageId"]
    progress["resent_at"] = stamp()
    save(progress)
    print("Exact reviewed envelope resent once", flush=True)


def writer_identifier(endpoint):
    clusters = aws("rds", "describe-db-clusters").get("DBClusters", [])
    matches = [cluster for cluster in clusters if cluster.get("Endpoint") == endpoint]
    if len(matches) != 1:
        raise SmokeError("Terraform database endpoint does not identify one Aurora cluster")
    writers = [member["DBInstanceIdentifier"] for member in matches[0].get("DBClusterMembers", [])
               if member.get("IsClusterWriter")]
    if len(writers) != 1:
        raise SmokeError("Aurora cluster does not identify exactly one writer")
    return writers[0]


def verify_stopped(cluster, definition, progress):
    for record in progress["tasks"].values():
        task = describe(cluster, [record["arn"]])
        if len(task) != 1 or task[0].get("lastStatus") != "STOPPED":
            raise SmokeError("A tracked smoke task has not stopped")
    family = definition.rsplit("/", 1)[-1].split(":", 1)[0]
    for desired in ("PENDING", "RUNNING"):
        active = aws("ecs", "list-tasks", "--cluster", cluster, "--family", family,
                     "--desired-status", desired).get("taskArns", [])
        if any(task.get("taskDefinitionArn") == definition for task in describe(cluster, active)):
            raise SmokeError("An evaluation-family task remains active")


def wait_for_idle(writer, after):
    deadline = timestamp(after) + IDLE_LIMIT
    while True:
        now = datetime.now(timezone.utc)
        start = max(timestamp(after), now - timedelta(minutes=15))
        data = aws("cloudwatch", "get-metric-statistics", "--namespace", "AWS/RDS",
                   "--metric-name", "ServerlessDatabaseCapacity", "--dimensions",
                   f"Name=DBInstanceIdentifier,Value={writer}", "--start-time", start.isoformat(),
                   "--end-time", now.isoformat(), "--period", "60", "--statistics", "Minimum")
        points = sorted((timestamp(point["Timestamp"]), point["Minimum"])
                        for point in data.get("Datapoints", []) if timestamp(point["Timestamp"]) >= timestamp(after))
        if len(points) >= 2 and points[-1][1] == points[-2][1] == 0 and (
                points[-1][0] - points[-2][0]) <= timedelta(minutes=2) and (
                now - points[-1][0]) <= timedelta(minutes=4):
            print("Aurora writer reported 0 ACU in two post-task metric periods", flush=True)
            return
        latest = "missing" if not points else f"{points[-1][1]} ACU"
        print(f"Waiting for Aurora idle metric; latest post-task sample: {latest}", flush=True)
        if now >= deadline:
            raise SmokeError("Aurora did not report two post-task 0-ACU periods within 30 minutes")
        time.sleep(60)


def main():
    body, envelope = load_envelope()
    progress = load_progress(hashlib.sha256(body.encode("utf-8")).hexdigest())
    cluster = output("ecs_cluster_name")
    definition = output("sqs_evaluation_task_definition_arn")
    queue_url = output("sqs_queue_url")
    subnet = output("task_subnet_ids")[0]
    security_group = output("task_security_group_id")
    endpoint = output("database_endpoint")
    network = {"awsvpcConfiguration": {"subnets": [subnet], "securityGroups": [security_group],
              "assignPublicIp": "ENABLED"}}
    task_definition = aws("ecs", "describe-task-definition", "--task-definition", definition)
    container = next(item for item in task_definition["taskDefinition"]["containerDefinitions"]
                     if item["name"] == "job-agent")
    options = container["logConfiguration"]["options"]
    log_group, prefix = options["awslogs-group"], options["awslogs-stream-prefix"]

    first, first_stop = task_for_stage(progress, "first_evaluation", cluster, definition, "evaluate",
                                       timestamp(envelope["occurredAt"]), network, log_group, prefix)
    require_invocation(first, duplicate=False)
    status, status_stop = task_for_stage(progress, "first_status", cluster, definition, "status",
                                         timestamp(first_stop), network, log_group, prefix)
    evaluation_id = require_status(status)
    progress["evaluation_id"] = evaluation_id
    save(progress)
    resend_once(progress, queue_url, body)
    second, second_stop = task_for_stage(progress, "duplicate_evaluation", cluster, definition, "evaluate",
                                         timestamp(progress["resent_at"]), network, log_group, prefix)
    require_invocation(second, duplicate=True)
    check, check_stop = task_for_stage(progress, "duplicate_status", cluster, definition, "status",
                                       timestamp(second_stop), network, log_group, prefix)
    require_status(check, previous=evaluation_id)
    verify_stopped(cluster, definition, progress)
    if not progress.get("idle_verified"):
        wait_for_idle(writer_identifier(endpoint), check_stop)
        progress["idle_verified"] = True
        save(progress)
    print(f"Smoke complete: event {EVENT_ID}, persisted evaluation {evaluation_id}, duplicate safe", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (SmokeError, KeyError, IndexError, ValueError) as error:
        print(f"Smoke stopped: {error}. Progress: {PROGRESS}", file=sys.stderr)
        sys.exit(1)
