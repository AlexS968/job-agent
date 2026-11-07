#!/usr/bin/env python3
"""Run one reviewed batch task; keep its identity locally for safe resumption."""

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
STATE = Path("/tmp/job-agent-batch-pipeline-progress.json")
PROFILE = "job-agent"
REGION = "us-east-1"


def call(args):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=75, check=False)
    if result.returncode:
        raise RuntimeError(f"{args[0]} command failed; inspect local configuration or AWS task metadata")
    return result.stdout.strip()


def aws(service, operation, *args):
    return json.loads(call(["aws", "--profile", PROFILE, "--region", REGION, "--no-cli-pager",
                            "--output", "json", service, operation, *args]))


def output(name):
    value = json.loads(call(["terraform", "-chdir=infra/terraform", "output", "-json", name]))
    if not value:
        raise RuntimeError(f"Missing reviewed Terraform output: {name}")
    return value


def save(state):
    descriptor, temporary = tempfile.mkstemp(prefix=".job-agent-batch-", dir=STATE.parent)
    try:
        with os.fdopen(descriptor, "w") as file:
            json.dump(state, file)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temporary, STATE)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def load():
    if STATE.is_symlink():
        raise RuntimeError("Progress path must not be a symlink")
    if STATE.exists():
        stat = STATE.stat()
        if stat.st_uid != os.getuid() or stat.st_mode & 0o077:
            raise RuntimeError("Progress file must be owned by this user and mode 0600")
        return json.loads(STATE.read_text())
    return {}


def task(cluster, arn):
    tasks = aws("ecs", "describe-tasks", "--cluster", cluster, "--tasks", arn).get("tasks", [])
    if len(tasks) != 1:
        raise RuntimeError("Tracked task unavailable; inspect ECS before another launch")
    return tasks[0]


def stamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def wait_task(cluster, arn):
    previous = None
    while True:
        current = task(cluster, arn)
        status = current.get("lastStatus")
        if status != previous:
            print(f"Batch task {arn.rsplit('/', 1)[-1]}: {status}", flush=True)
            previous = status
        if status == "STOPPED":
            container = next((item for item in current.get("containers", [])
                              if item.get("name") == "job-agent"), {})
            code = container.get("exitCode")
            print(f"Batch task exit code: {code}", flush=True)
            return current, code
        if datetime.now(timezone.utc) >= stamp(current["createdAt"]) + timedelta(minutes=25):
            break
        time.sleep(15)
    aws("ecs", "stop-task", "--cluster", cluster, "--task", arn,
        "--reason", "Bounded Job Agent batch timeout")
    raise RuntimeError("25-minute task bound reached; stop requested, confirm STOPPED")


def report(arn, definition):
    container = next(item for item in definition["containerDefinitions"] if item["name"] == "job-agent")
    options = container["logConfiguration"]["options"]
    stream = f"{options['awslogs-stream-prefix']}/job-agent/{arn.rsplit('/', 1)[-1]}"
    for attempt in range(7):
        lines = []
        for phrase in ('"Batch pipeline"', '"SUITABLE vacancy"', '"NEEDS_REVIEW vacancy"'):
            events = aws("logs", "filter-log-events", "--log-group-name", options["awslogs-group"],
                         "--log-stream-names", stream, "--filter-pattern", phrase)
            lines.extend(event["message"].strip() for event in events.get("events", []))
        selected = shortlist_lines(lines)
        if selected is not None:
            for line in selected:
                print(line, flush=True)
            return
        if attempt < 6:
            time.sleep(10)
    raise RuntimeError("Batch summary log unavailable; inspect task logs before rerun")


def shortlist_lines(lines):
    summaries = [line for line in lines if "Batch pipeline: outcome=" in line]
    if not summaries:
        return None
    summary = summaries[-1]
    counts = re.search(r"\bsuitable=(\d+), needs_review=(\d+)\b", summary)
    if counts is None:
        return None
    expected = int(counts.group(1)) + int(counts.group(2))
    if expected > 20:
        raise RuntimeError("Batch shortlist exceeds the configured report bound")
    entries = [line for line in lines if "SUITABLE vacancy: id=" in line
               or "NEEDS_REVIEW vacancy: id=" in line]
    if len(entries) != expected:
        return None
    return [summary, *(entries if entries else ["Shortlist empty: suitable=0, needs_review=0"])]


def idle(endpoint, after):
    clusters = aws("rds", "describe-db-clusters").get("DBClusters", [])
    matches = [cluster for cluster in clusters if cluster.get("Endpoint") == endpoint]
    if len(matches) != 1:
        raise RuntimeError("Database endpoint did not identify one cluster")
    writers = [member["DBInstanceIdentifier"] for member in matches[0]["DBClusterMembers"]
               if member.get("IsClusterWriter")]
    if len(writers) != 1:
        raise RuntimeError("Expected one Aurora writer")
    until = time.monotonic() + 30 * 60
    while time.monotonic() < until:
        now = datetime.now(timezone.utc)
        result = aws("cloudwatch", "get-metric-statistics", "--namespace", "AWS/RDS",
                     "--metric-name", "ServerlessDatabaseCapacity", "--dimensions",
                     f"Name=DBInstanceIdentifier,Value={writers[0]}", "--start-time", after.isoformat(),
                     "--end-time", now.isoformat(), "--period", "60", "--statistics", "Minimum")
        points = sorted((stamp(point["Timestamp"]), point["Minimum"])
                        for point in result.get("Datapoints", []) if stamp(point["Timestamp"]) >= after)
        if len(points) >= 2 and points[-1][1] == points[-2][1] == 0 and (
                points[-1][0] - points[-2][0]) <= timedelta(minutes=2):
            print("Aurora writer: two post-task 0-ACU metric periods", flush=True)
            return
        print("Waiting for Aurora idle metric", flush=True)
        time.sleep(60)
    raise RuntimeError("Aurora idle was not observed within 30 minutes")


def main():
    state = load()
    cluster = output("ecs_cluster_name")
    definition_arn = output("batch_pipeline_task_definition_arn")
    definition = aws("ecs", "describe-task-definition", "--task-definition", definition_arn)["taskDefinition"]
    if state.get("definition") and state["definition"] != definition_arn:
        raise RuntimeError("Progress refers to another task definition; review before rerun")
    if "arn" not in state:
        if state:
            raise RuntimeError("Launch intent has no task ARN; inspect ECS before retrying")
        state = {"definition": definition_arn, "intent": datetime.now(timezone.utc).isoformat()}
        save(state)
        network = {"awsvpcConfiguration": {"subnets": output("task_subnet_ids"),
                   "securityGroups": [output("task_security_group_id")], "assignPublicIp": "ENABLED"}}
        launched = aws("ecs", "run-task", "--cluster", cluster, "--launch-type", "FARGATE",
                       "--task-definition", definition_arn, "--count", "1",
                       "--network-configuration", json.dumps(network))
        if launched.get("failures") or len(launched.get("tasks", [])) != 1:
            raise RuntimeError("ECS rejected launch; inspect before retrying")
        state["arn"] = launched["tasks"][0]["taskArn"]
        save(state)
    current, code = wait_task(cluster, state["arn"])
    report(state["arn"], definition)
    if code != 0:
        raise RuntimeError("Batch task failed; inspect safe logs and backlog before any rerun")
    family = definition_arn.rsplit("/", 1)[-1].split(":", 1)[0]
    for status in ("PENDING", "RUNNING"):
        active = aws("ecs", "list-tasks", "--cluster", cluster, "--family", family,
                     "--desired-status", status).get("taskArns", [])
        if active:
            raise RuntimeError("Batch-family task still active")
    if not state.get("idle_verified"):
        idle(output("database_endpoint"), stamp(current["stoppedAt"]))
        state["idle_verified"] = True
        save(state)
    print("Batch acceptance complete; inspect reported backlog before another invocation", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        print(f"Batch runner stopped: {error}. Private progress: {STATE}", file=sys.stderr)
        sys.exit(1)
