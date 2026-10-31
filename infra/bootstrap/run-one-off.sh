#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! "$1" =~ ^(prepare|migrate|finalize|app)$ ]]; then
  echo 'Usage: bash infra/bootstrap/run-one-off.sh prepare|migrate|finalize|app' >&2
  exit 2
fi

mode="$1"
profile="${AWS_PROFILE:-job-agent}"
region="us-east-1"
task_arn=""
task_stopped=false

stop_if_running() {
  if [[ -n "$task_arn" && "$task_stopped" != true ]]; then
    aws --profile "$profile" --region "$region" ecs stop-task \
      --cluster "$cluster" --task "$task_arn" --reason 'Controlled Phase 5B runner ending' >/dev/null 2>&1 || true
    echo "Stop requested for $task_arn; verify STOPPED in ECS" >&2
  fi
}
trap stop_if_running EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cluster="$(terraform -chdir=infra/terraform output -raw ecs_cluster_name)"
subnets="$(terraform -chdir=infra/terraform output -json task_subnet_ids)"
security_group="$(terraform -chdir=infra/terraform output -raw task_security_group_id)"
case "$mode" in
  prepare|finalize)
    definition="$(terraform -chdir=infra/terraform output -raw bootstrap_task_definition_arn)"
    container="bootstrap"
    max_seconds=900
    ;;
  migrate)
    definition="$(terraform -chdir=infra/terraform output -raw migration_task_definition_arn)"
    container="job-agent"
    max_seconds=1200
    ;;
  app)
    definition="$(terraform -chdir=infra/terraform output -raw application_task_definition_arn)"
    container="job-agent"
    max_seconds=900
    ;;
esac

if [[ -z "$definition" || "$definition" == "null" ]]; then
  echo "Task definition for $mode is not enabled in the reviewed Terraform state" >&2
  exit 1
fi

network="$(jq -nc --argjson subnets "$subnets" --arg group "$security_group" \
  '{awsvpcConfiguration:{subnets:$subnets,securityGroups:[$group],assignPublicIp:"ENABLED"}}')"
override='{}'
if [[ "$mode" == finalize ]]; then
  override='{"containerOverrides":[{"name":"bootstrap","command":["finalize"]}]}'
fi

response="$(aws --profile "$profile" --region "$region" ecs run-task \
  --cluster "$cluster" --task-definition "$definition" --launch-type FARGATE \
  --count 1 --network-configuration "$network" --overrides "$override" --output json)"
if ! jq -e '.failures | length == 0' >/dev/null <<<"$response"; then
  echo 'ECS rejected RunTask; inspect ECS failures without exposing credentials' >&2
  exit 1
fi
task_arn="$(jq -er '.tasks[0].taskArn' <<<"$response")"
echo "Started $mode task: $task_arn"

deadline=$((SECONDS + max_seconds))
while (( SECONDS < deadline )); do
  task="$(aws --profile "$profile" --region "$region" ecs describe-tasks \
    --cluster "$cluster" --tasks "$task_arn" --output json)"
  if [[ "$(jq -r '.tasks[0].lastStatus // ""' <<<"$task")" == STOPPED ]]; then
    task_stopped=true
    exit_code="$(jq -er --arg container "$container" \
      '.tasks[0].containers[] | select(.name == $container) | .exitCode' <<<"$task")"
    echo "$mode task stopped with container exit code $exit_code"
    exit "$exit_code"
  fi
  sleep 10
done

echo "$mode task exceeded its ${max_seconds}s limit; requesting stop" >&2
stop_if_running
for attempt in {1..12}; do
  task="$(aws --profile "$profile" --region "$region" ecs describe-tasks \
    --cluster "$cluster" --tasks "$task_arn" --output json)"
  if [[ "$(jq -r '.tasks[0].lastStatus // ""' <<<"$task")" == STOPPED ]]; then
    task_stopped=true
    break
  fi
  sleep 10
done
exit 124
