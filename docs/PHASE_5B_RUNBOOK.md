# Phase 5B: first controlled cloud database check

Status: prepared locally for review; the original Phase 5B images were published to ECR, but **no Phase 5B task or cloud database bootstrap has run**. Phase 5A and the Aurora activity alert are merged. The owner reports a Paid Plan, retained credits, private Aurora PostgreSQL 17.11 cluster `job-agent-p5a-db` and writer `job-agent-p5a-writer` in us-east-1, 0–2 ACU with a 300-second auto-pause interval, and an initial period at 0 ACU. The SNS subscription is confirmed: a controlled ALARM state test delivered an email and the real metric-driven return to OK delivered another. This does not verify an application wake/sleep cycle or a real one-hour capacity alarm. No AWS application task, DB bootstrap or cloud migration has been verified.

## Bootstrap image security review (2026-10-31)

The published application image `app-bd3b42e4050f` (`sha256:b1c12224f9ccdbe171434eeeda675702cb5b6e1e05452371f53a46cc88bd4536`) completed ECR basic scanning without findings; this is not a complete Java dependency audit. The separate published bootstrap image `bootstrap-bd3b42e4050f` (`sha256:4d94e0cc4212cfc6c1f00577d1f42ca83551a81e07fe887cbc211edb9aa5d9f8`) had eight HIGH, two MEDIUM and two LOW ECR findings. The supplied findings export contains the eight HIGH entries only; the MEDIUM/LOW identifiers and applicability cannot be assessed from it. Do not run that bootstrap image.

The original Debian Bookworm image contained `util-linux`/`mount` 2.38.1-5+deb12u3, `glibc` 2.36-9+deb12u14 and `zlib` 1:1.2.13.dfsg-1. Debian's current security tracker gives these dispositions (the package version alone does not establish a backported fix):

| Finding | Official package status and relevant trigger | Local remediation |
| --- | --- | --- |
| [CVE-2026-76642](https://security-tracker.debian.org/tracker/CVE-2026-76642) | Bookworm `util-linux` vulnerable; mount processing of attacker-controlled mount options. | `util-linux` absent in revised image. |
| [CVE-2026-53615](https://security-tracker.debian.org/tracker/CVE-2026-53615) | Bookworm `util-linux` vulnerable; probing an attacker-controlled DOS extended-partition image through `libblkid`. | `util-linux` absent. |
| [CVE-2026-78410](https://security-tracker.debian.org/tracker/CVE-2026-78410) | Bookworm `util-linux` vulnerable; an fstab-authorized restricted bind mount with an attacker-replaceable source path. | `util-linux` absent. |
| [CVE-2026-78408](https://security-tracker.debian.org/tracker/CVE-2026-78408) | Bookworm `util-linux` vulnerable; privileged `nsenter --join-cgroup` against an attacker-controlled target. | `util-linux` absent. |
| [CVE-2026-78409](https://security-tracker.debian.org/tracker/CVE-2026-78409) | Debian marks Bookworm **not affected**: relevant code arrived in util-linux 2.42; ECR reported it against 2.38. | `util-linux` absent; ECR classification contradicted by Debian. |
| [CVE-2026-53613](https://security-tracker.debian.org/tracker/CVE-2026-53613) | Bookworm `util-linux` vulnerable; privileged namespace entry. | `util-linux` absent. |
| [CVE-2026-84243](https://security-tracker.debian.org/tracker/CVE-2026-84243) | Debian marks Bookworm `glibc` vulnerable, with no fixed Debian version listed; exploitation needs attacker influence over locale/message-catalog lookup. | Revised Alpine image uses musl; `glibc` absent. |
| [CVE-2026-85091](https://security-tracker.debian.org/tracker/CVE-2026-85091) | Debian marks Bookworm `zlib` vulnerable even though the supplied upstream description names versions 1.3.1.2–1.3.2; this discrepancy requires distribution-level assessment. | Alpine 3.24 has `zlib` 1.3.2-r1, listed as fixed in [Alpine's security database](https://secdb.alpinelinux.org/v3.24/main.json) and [release notice](https://www.alpinelinux.org/posts/Alpine-3.21.9-3.22.7-3.23.7-and-3.24.3-released.html). |

These util-linux paths are not used by the Python bootstrap task; ECS grants no privileged mount, host device or inbound access. This reduces exposure but non-root execution and a read-only root filesystem alone are not proof of safety. Forced removal of Debian's essential `util-linux` was rejected. The revised image uses the digest-pinned official Python 3.13 Alpine 3.24 base, supported through June 2028 by [Alpine's release schedule](https://www.alpinelinux.org/releases/), and retains Python, boto3, psycopg, SCRAM and the RDS CA bundle. The pinned base digest is `sha256:7984cf56ceaddebe4fefeb75d64000b9f45d8b77b11a09b24524da6cdc3af0c8` (linux/amd64 child `sha256:7712cfd23e0501f5cb58faa5e6e0d515c9939bdbce866921799b31e696a1d13c`). A distribution switch changes libc and must be checked in the first cloud task; local tests do not prove RDS/ECS behavior.

Local `linux/amd64` build and isolated PostgreSQL bootstrap tests passed with UID/GID 10001 and read-only root. Trivy 0.76.1 reported zero OS vulnerabilities for Alpine 3.24.3. Scanning the image tar reported eight Python findings from inherited metadata (`msgpack` 1.1.2, `setuptools` 70.3.0 and `urllib3` 2.7.0), but a scan of the exported runtime filesystem reported **zero vulnerabilities**; runtime inspection found neither `msgpack` nor `setuptools` and found `urllib3` 2.8.0. This discrepancy is documented rather than suppressed. The future ECR scan can differ: review its full findings before enabling the bootstrap gate. Any remaining HIGH finding needs an explicit applicability and owner decision, not silent acceptance.

### Publish the revised bootstrap image after review

The original immutable ECR tags must remain untouched. Reuse the published application digest above; this change does not require republishing the application image. From the reviewed, committed revision, run the following **later** (not executed during this review):

```sh
export AWS_PROFILE=job-agent
docker buildx build --platform linux/amd64 --file infra/bootstrap/Dockerfile --tag job-agent-bootstrap:security-local --load .
BOOTSTRAP_TEST_IMAGE=job-agent-bootstrap:security-local bash infra/bootstrap/test-local.sh
docker image inspect job-agent-bootstrap:security-local --format '{{.Architecture}} {{.Config.User}}'
ECR_REPO="$(terraform -chdir=infra/terraform output -raw ecr_repository_url)"
BOOTSTRAP_TAG="bootstrap-security-$(git rev-parse --short=12 HEAD)"
aws --profile job-agent --region us-east-1 ecr describe-images --repository-name job-agent-p5a --image-ids imageTag="$BOOTSTRAP_TAG"
# The preceding lookup must report that this new tag does not exist; then authenticate and push.
aws --profile job-agent --region us-east-1 ecr get-login-password | docker login --username AWS --password-stdin "${ECR_REPO%%/*}"
docker tag job-agent-bootstrap:security-local "$ECR_REPO:$BOOTSTRAP_TAG"
docker push "$ECR_REPO:$BOOTSTRAP_TAG"
BOOTSTRAP_DIGEST="$(aws --profile job-agent --region us-east-1 ecr describe-images --repository-name job-agent-p5a --image-ids imageTag="$BOOTSTRAP_TAG" --query 'imageDetails[0].imageDigest' --output text)"
APP_DIGEST='sha256:b1c12224f9ccdbe171434eeeda675702cb5b6e1e05452371f53a46cc88bd4536'
printf 'Reviewed bootstrap digest: %s\n' "$BOOTSTRAP_DIGEST"
docker buildx imagetools inspect "$ECR_REPO@$BOOTSTRAP_DIGEST"
aws --profile job-agent --region us-east-1 ecr describe-image-scan-findings --repository-name job-agent-p5a --image-id imageDigest="$BOOTSTRAP_DIGEST" --query '[imageScanStatus,imageScanFindings.findingSeverityCounts]'
aws --profile job-agent --region us-east-1 ecr describe-image-scan-findings --repository-name job-agent-p5a --image-id imageDigest="$BOOTSTRAP_DIGEST" --query 'imageScanFindings.findings[*].[name,severity,description,uri]' --output table
```

ECR is configured to scan on push. Wait for scanning to reach COMPLETE and inspect **all** finding details; the AWS CLI paginates the second query automatically unless pagination is disabled. The owner must approve any unresolved HIGH finding. Use the new `BOOTSTRAP_DIGEST` with the existing `APP_DIGEST` in the fresh Terraform plan below; never reuse the vulnerable bootstrap digest or an old saved plan.

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

The original `linux/amd64` application image is already published at the digest recorded above. Publish and review only the revised bootstrap image using the security-review commands above. Require `linux/amd64` in its manifest and a complete ECR finding review. In the **ignored** `terraform.tfvars`, set `enable_bootstrap=true` and `bootstrap_image_digest` to the newly printed `sha256:` value; keep `enable_runtime=false`. Review a **fresh** plan for only the bootstrap task definition and its temporary IAM role/policy, then apply it only with separate owner authorization:

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
