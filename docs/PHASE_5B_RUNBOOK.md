# Phase 5B: first controlled cloud database check

Status: prepared locally for review; **no Phase 5B AWS action has run**. Phase 5A and the Aurora activity alert are merged. The owner reports a Paid Plan, retained credits, private Aurora PostgreSQL 17.11 cluster `job-agent-p5a-db` and writer `job-agent-p5a-writer` in us-east-1, 0–2 ACU with a 300-second auto-pause interval, and an initial period at 0 ACU. The SNS subscription is confirmed: a controlled ALARM state test delivered an email and the real metric-driven return to OK delivered another. This does not verify an application wake/sleep cycle or a real one-hour capacity alarm. No AWS application task, DB bootstrap or cloud migration has been verified.

## Contract and security boundary

The existing application [entrypoint](../src/main/java/com/alexs/jobagent/JobAgentApplication.java) has **no default mode**. With no arguments it validates Flyway, reads only `COUNT(*)` from `agent_runs`, logs a safe count, closes Spring/Hikari and exits. `SPRING_PROFILES_ACTIVE=cloud-app` selects the existing validate-only Flyway strategy. The migration task omits that profile and applies the V1–V13 migrations. Neither task definition supplies `--mode=synthetic`, source import, search, dispatch, extraction or another business action. The reviewed image digest is fixed in both task definitions. JDBC uses the private cluster endpoint and `sslmode=verify-full` with the bundled us-east-1 RDS CA bundle. ECS task subnets have public IPv4 for ECR/Secrets Manager/logs, no inbound task rule and DB egress only to the private DB security group; the DB ingress permits only that task security group.

The one-off bootstrap task is separately gated by `enable_bootstrap=false` by default, uses its own 0.25-vCPU/0.5-GB Fargate definition, image digest and temporary task role. The task role can read only the RDS-managed administrator secret and the two named role secrets, and create/describe only those two role secrets. The regular application task role has no AWS actions. The ECS execution role pulls ECR, writes task logs and later retrieves only the dedicated DB password secrets for the app/migrator task definitions. The bootstrap task fetches the administrator secret **inside the task**, uses TLS verification, generates independent random passwords in memory and creates `job-agent-p5a-migration-db` and `job-agent-p5a-app-db` with JSON `password` fields. Terraform receives secret **ARNs only**, never values. The bootstrap image runs as UID 10001 with a read-only root filesystem. Do not print task environment, secret values, SQL bind values, full plan/state or task-definition secrets.

Bootstrap `prepare` refuses a pre-existing Flyway history table or any existing target role/secret. It never silently rotates or overwrites a credential. Because Secrets Manager and PostgreSQL cannot share one transaction, a crash can leave partial state; stop and review the actual roles/secrets before any rerun. Do not delete a secret or role to force a retry without reconciling which password was committed. The script stores only a SCRAM verifier in role-creation SQL, not the plaintext password. The migrator gets `CONNECT`, `USAGE` and `CREATE` on `public` and owns objects it creates. The app gets `CONNECT` and schema `USAGE`, with default DML and sequence privileges on objects the migrator creates, but no schema `CREATE` or role administration. The Flyway history table temporarily receives default table grants during migration; **do not run the app until `finalize`**. `finalize` requires migration history, revokes app writes on it, grants `SELECT`, connects as the app, reads history and performs rolled-back `CREATE TABLE` and `CREATE SCHEMA` probes that must fail for insufficient privilege. Repeating `finalize` is safe; repeating `prepare` stops for review. Validate the SQL grants and privilege evidence before any business work. These roles are separate from the managed administrator.

## Owner execution after review and publication

Run each stage only after inspecting the previous result. The commands below are **NOT EXECUTED** in this task. Keep the existing remote backend and local ignored `backend.hcl`, `terraform.tfvars` and `activity-alert.auto.tfvars`; do not start new state. Set `AWS_PROFILE=job-agent` in the owner's shell, then first check the target account and region:

```sh
export AWS_PROFILE=job-agent
aws --profile job-agent --region us-east-1 sts get-caller-identity --query '[Account,Arn]' --output table
terraform -chdir=infra/terraform init -input=false -backend-config=backend.hcl
terraform -chdir=infra/terraform plan -input=false -var-file=terraform.tfvars -out=phase5b-baseline.tfplan
terraform -chdir=infra/terraform show -no-color phase5b-baseline.tfplan
```

Stop if the plan replaces any deployed network, database, bucket, repository, alarm or state-related resource. A clean baseline plan should have no changes before enabling new gates. Saved plans and state can expose identifiers and sensitive inputs; keep them private. Never reuse either historical Phase 5A plan.

Build and locally test **both** `linux/amd64` images from the reviewed revision before publication. The bootstrap image is separate so it does not add administration tools to the application image. ECR tags are immutable and only a reviewed manifest digest goes into Terraform:

```sh
docker buildx build --platform linux/amd64 --file Dockerfile --tag job-agent:phase5b-local --load .
docker buildx build --platform linux/amd64 --file infra/bootstrap/Dockerfile --tag job-agent-bootstrap:phase5b-local --load .
bash infra/bootstrap/test-local.sh
docker image inspect job-agent:phase5b-local --format '{{.Architecture}}'
docker image inspect job-agent-bootstrap:phase5b-local --format '{{.Architecture}}'
ECR_REPO="$(terraform -chdir=infra/terraform output -raw ecr_repository_url)"
APP_TAG="app-$(git rev-parse --short=12 HEAD)"
BOOTSTRAP_TAG="bootstrap-$(git rev-parse --short=12 HEAD)"
aws --profile job-agent --region us-east-1 ecr get-login-password | docker login --username AWS --password-stdin "${ECR_REPO%%/*}"
docker tag job-agent:phase5b-local "$ECR_REPO:$APP_TAG"
docker tag job-agent-bootstrap:phase5b-local "$ECR_REPO:$BOOTSTRAP_TAG"
docker push "$ECR_REPO:$APP_TAG"
docker push "$ECR_REPO:$BOOTSTRAP_TAG"
APP_DIGEST="$(aws --profile job-agent --region us-east-1 ecr describe-images --repository-name job-agent-p5a --image-ids imageTag="$APP_TAG" --query 'imageDetails[0].imageDigest' --output text)"
BOOTSTRAP_DIGEST="$(aws --profile job-agent --region us-east-1 ecr describe-images --repository-name job-agent-p5a --image-ids imageTag="$BOOTSTRAP_TAG" --query 'imageDetails[0].imageDigest' --output text)"
printf 'Application digest: %s\nBootstrap digest: %s\n' "$APP_DIGEST" "$BOOTSTRAP_DIGEST"
docker buildx imagetools inspect "$ECR_REPO@$APP_DIGEST"
docker buildx imagetools inspect "$ECR_REPO@$BOOTSTRAP_DIGEST"
```

Require `amd64` in both local inspections and `linux/amd64` in the published manifests; review ECR scan findings before running. If a tag already exists, do not overwrite it; compare its digest or use a new reviewed immutable tag. In the **ignored** `terraform.tfvars`, set `enable_bootstrap=true` and `bootstrap_image_digest` to the exact printed `sha256:` value; keep `enable_runtime=false`. Review a **fresh** plan for only the bootstrap task definition and its temporary IAM role/policy, then apply it only with separate owner authorization:

```sh
terraform -chdir=infra/terraform plan -input=false -var-file=terraform.tfvars -out=phase5b-bootstrap.tfplan
terraform -chdir=infra/terraform show -no-color phase5b-bootstrap.tfplan
terraform -chdir=infra/terraform apply -input=false phase5b-bootstrap.tfplan
bash infra/bootstrap/run-one-off.sh prepare
```

The runner uses the configured task subnets/security group, assigns a task public IP, prints task ARN and container exit code, and stops the task on a local timeout (15 minutes for bootstrap/app, 20 for migration). It sends no credentials in CLI arguments. If the runner loses its connection or the owner's machine stops, independently check ECS for a still-running task and stop it; the runner cannot enforce a deadline after its own process dies. An uncertain `prepare` result requires inspection of roles and secrets before any retry. No automatic secret deletion or password rotation occurs.

After `prepare` exits 0, obtain **ARNs only** (never `get-secret-value`) and place those plus `APP_DIGEST` in the ignored `terraform.tfvars` as `migration_db_secret_arn`, `app_db_secret_arn`, `image_digest`; set `enable_runtime=true`. Keep bootstrap enabled through finalization. Review a new plan for the execution-role secret grant and two task definitions, then apply it. The migration definition has no `cloud-app` profile; app has `SPRING_PROFILES_ACTIVE=cloud-app` and neither has a batch mode argument:

```sh
aws --profile job-agent --region us-east-1 secretsmanager describe-secret --secret-id job-agent-p5a-migration-db --query ARN --output text
aws --profile job-agent --region us-east-1 secretsmanager describe-secret --secret-id job-agent-p5a-app-db --query ARN --output text
terraform -chdir=infra/terraform plan -input=false -var-file=terraform.tfvars -out=phase5b-runtime.tfplan
terraform -chdir=infra/terraform show -no-color phase5b-runtime.tfplan
terraform -chdir=infra/terraform apply -input=false phase5b-runtime.tfplan
bash infra/bootstrap/run-one-off.sh migrate
bash infra/bootstrap/run-one-off.sh finalize
bash infra/bootstrap/run-one-off.sh app
bash infra/bootstrap/run-one-off.sh app
```

**Stop on any nonzero exit.** Inspect the CloudWatch `/ecs/job-agent-p5a` task streams by task ID for the Flyway migration and `Database ready` messages; never copy credential-bearing logs. A fresh DB should apply V1–V13 once. `finalize` must log denied application DDL and read-only migration history before the first app run. Both app runs must exit 0, validate migrations and log the same `agent_runs` count without source/domain writes. If a task fails, retain the DB and state, inspect its stopped reason and logs, and correct the cause with a fresh plan or controlled rerun; never reset Flyway history or rerun `prepare` blindly. The live RDS behavior and ECS exit paths are still unverified until this sequence runs.

After both app runs, set `enable_bootstrap=false` in ignored `terraform.tfvars`, review a **fresh** plan that removes only the bootstrap task definition and temporary IAM role/policy, and apply it. Confirm no RUNNING tasks remain, then wait at least the configured five idle minutes plus CloudWatch publication delay and inspect the writer instance metric for a return to 0 ACU:

```sh
terraform -chdir=infra/terraform plan -input=false -var-file=terraform.tfvars -out=phase5b-remove-bootstrap.tfplan
terraform -chdir=infra/terraform show -no-color phase5b-remove-bootstrap.tfplan
terraform -chdir=infra/terraform apply -input=false phase5b-remove-bootstrap.tfplan
aws --profile job-agent --region us-east-1 ecs list-tasks --cluster job-agent-p5a-batch --desired-status RUNNING
aws --profile job-agent --region us-east-1 cloudwatch get-metric-statistics \
  --namespace AWS/RDS --metric-name ServerlessDatabaseCapacity \
  --dimensions Name=DBInstanceIdentifier,Value=job-agent-p5a-writer \
  --statistics Minimum --period 60 \
  --start-time "$(date -u -v-30M +%Y-%m-%dT%H:%M:%SZ)" \
  --end-time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --query 'Datapoints[*].[Timestamp,Minimum]' --output table
```

Confirm the 0-ACU datapoints are **after** both tasks stopped. Keep the cluster, state, image digests, role secrets, and runtime task definitions for later controlled work; do not destroy the database. The temporary bootstrap role/task definition must be gone, but ECS may retain stopped-task history and ECR retains the bootstrap image until separately reviewed cleanup. The activity email alert warns after approximately one hour of nonzero writer capacity; it does not stop spending. No schedule or paid source/LLM call is enabled.

## Cost and remaining permissions

This one-time sequence incurs Fargate CPU/memory time for two bootstrap, one migration and two app tasks, task public IPv4 time, Aurora ACU-hours while awake, logs, ECR image storage and requests. The two new database secrets have recurring [Secrets Manager](https://aws.amazon.com/secrets-manager/pricing/) storage charges of about **$0.80/month** before applicable credits; one-off task/DB usage depends on measured duration and capacity. The historical Phase 5A cost sketch is not a measured bill. Credits may offset eligible charges but do not guarantee payment coverage, and the existing budget/ACU emails are not hard caps. The bootstrap role/policy itself has no standing compute charge; remove it promptly after finalization. Owner-facing payment is actual eligible charges less applied credits; check Billing/Cost Explorer rather than assuming zero.

The owner needs ECR login/push/describe, ECS RunTask/DescribeTasks/StopTask/ListTasks, CloudWatch read, Secrets Manager **DescribeSecret only** to collect role ARNs, and IAM PassRole for the reviewed task/execution roles, in addition to the existing restricted Terraform plan/apply permissions for temporary IAM/ECS resources. The bootstrap **task role**, not the owner's CLI, receives managed-master `GetSecretValue` and narrowly scoped role-secret Create/Describe/Get. The execution role later receives only role-secret `GetSecretValue`. Verify the account's permissions and any permission boundary before execution. Do not grant the CLI direct managed-master secret retrieval for this run.

## Local verification record (2026-10-31)

- `docker buildx build --platform linux/amd64 --file Dockerfile ... --load .` and the bootstrap-image equivalent passed. Both images inspected as `amd64` and UID/GID `10001:10001`; neither was published.
- `bash infra/bootstrap/test-local.sh` passed 2 tests against an isolated PostgreSQL 17.11 container, including SCRAM login, synthetic partial-secret failure with no role creation, fail-closed prepare replay, premature finalization rejection, default domain grants, migration-history read-only access, denied DDL and idempotent finalization. The bootstrap test image ran with a read-only root. This is not a test against RDS or real Secrets Manager.
- `./mvnw -B -Dit.test=BatchStartupIT verify` passed 104 unit tests and 17 PostgreSQL batch integration tests, no failures or skips. The first sandboxed attempt hit local socket/agent permission errors; a rerun with local Docker/JVM access passed. Java application code did not change, so the full integration suite was not repeated.
- Isolated `terraform init -backend=false -input=false -lockfile=readonly`, `terraform fmt -check -recursive infra/terraform`, `terraform validate`, Bash syntax, CI YAML parse and `git diff --check` passed. No account-backed plan was run.
- Trivy 0.76.1 `config --severity HIGH,CRITICAL` returned four findings: AWS-0079 (RDS AWS-managed key), AWS-0104 (broad HTTPS egress), AWS-0132 (S3 SSE-S3 rather than a customer-managed key), and AWS-0095 (unencrypted SNS alert topic). The first three were previously documented Phase 5A tradeoffs; the fourth belongs to the already merged activity alert. No finding was reported for the new bootstrap Terraform. The scan is **not clean**, and these findings require the existing cost/security review; no finding was suppressed.

Cloud ECR digest, IAM policy acceptance, task startup, Secrets Manager writes, RDS role bootstrap, migration, restricted app checks, task cleanup and return to 0 ACU are **pending owner execution**. An ambiguous RunTask response or runner failure may leave a task running; inspect ECS before retrying. The activity alarm is a warning, not a substitute for task supervision.
