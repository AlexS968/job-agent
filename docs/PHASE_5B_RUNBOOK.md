# Phase 5B: first controlled cloud database check

Status: the controlled Phase 5B cloud database check is complete based on owner-supplied task logs, Terraform results and CloudWatch observations from 2026-11-01. Bootstrap, migration, grant finalization and two validate-only application checks succeeded; temporary bootstrap IAM/task resources were removed and writer capacity returned to 0 ACU. Phase 5 as a whole remains incomplete. The earlier migration startup failure is retained below as history. The real one-hour activity-alarm trigger, restore, scheduled business work and long-term cost remain unverified.

## Verified controlled cloud sequence (owner-supplied evidence, 2026-11-01)

| Stage | Observed result |
| --- | --- |
| Images | Revised bootstrap tag `bootstrap-security-eab71854c060`, digest `sha256:c295d6799f505d9ca1d0f05cd355c550ff9cbde8c75072278b78ffb735a0d80c`, `linux/amd64`, UID/GID 10001; ECR basic scan COMPLETE with empty finding counts. Two isolated PostgreSQL tests passed. The application reused `sha256:b1c12224f9ccdbe171434eeeda675702cb5b6e1e05452371f53a46cc88bd4536` after the Terraform-only Hikari correction. ECR basic scanning is not a complete dependency audit; retain the Python scan discrepancy below. |
| Bootstrap | `prepare` task `dcf7451ec98340d1981ec75a8b9f1ead` exited 0 and created separate migration/application roles and secrets. Do not rerun it routinely. |
| Migration | First task `6b344313f5934bdc85cc57f85058ff16` exited 1 during startup. After the configuration correction, task `c6c854f182424954bbebfb591e8a7eae` exited 0. Logs show `sslmode=verify-full`, PostgreSQL 17.11, an initially empty schema, successful V1–V13 migration, `agent_runs` count 0 and completed Hikari shutdown. The original timeout's relationship to Aurora resume remains unproven. |
| Grants | `finalize` task `07ba236d46fd43e792d53d7426f67567` exited 0; its log reports migration history read-only to the app and table/schema DDL denied. |
| Application | Task-definition revision 2 ran twice: `4bfbf933fd3947dfa2b3d27ba2b2f90d` and `e2d2c034c5e4459caa9f9af1a03b414e`, both exit 0. Both logs show the `cloud-app` profile, validation of 13 migrations, `agent_runs` count 0 and Hikari shutdown. The second shutdown completed at 2026-11-01 14:50:47.193 UTC. This checks the no-mode database path; it is not an independent audit of every table. |
| Cleanup | Reviewed Terraform plan removed only the bootstrap task definition, IAM role and inline policy; apply reported 0 added, 0 changed, 3 destroyed. `enable_bootstrap=false`, `enable_runtime=true`; ECS RUNNING task list was empty. Database, role secrets and app/migration task definitions remain. |
| Capacity | Writer `ServerlessDatabaseCapacity` was 0 ACU through 15:39 Europe/Berlin, rose at 15:40 during the controlled sequence, later fell to 0.5 ACU, averaged about 0.01923 ACU at 15:56 and was 0 ACU at 15:57. On this date Berlin was UTC+1, so the second app shutdown at 14:50:47 UTC was 15:50:47 local. This demonstrates one observed wake/return-to-pause cycle, not a latency or billing guarantee. |

No source import, SQS delivery, schedule, paid LLM call or old Sheet cutover was exercised. The confirmed SNS subscription, controlled ALARM email and metric-driven OK email predate this sequence; a real one-hour prolonged-activity trigger remains untested. Notifications warn but never stop spending. Restore and measured recovery, complete operating cost and longer-term pause reliability remain open.

## Historical first migration failure and correction (2026-11-01)

Task `6b344313f5934bdc85cc57f85058ff16` exited 1. Its log showed Hikari starting and a Spring bind failure under `spring.datasource.hikari.connection`, caused by `java.net.SocketTimeoutException: Read timed out`. The previous Terraform environment used `SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` and `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`. [Spring Boot's canonical env conversion](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding.environment-variables) removes property-name dashes; the corrected names are `SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT` and `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE`. A subprocess integration test with actual OS environment and PostgreSQL reproduced the old binding failure and verified the corrected values of 60000 ms and 2 connections. This establishes a configuration defect, **not** the complete cause of the cloud socket timeout.

The application image contains pgJDBC 42.7.13 and HikariCP 7.0.2. [pgJDBC documents](https://jdbc.postgresql.org/documentation/use/) `connectTimeout` as a 10-second-default socket-connect limit (already 45 seconds in the cloud URL), `socketTimeout` as a zero-default read limit (already 120 seconds), `loginTimeout` as zero by default, and `sslResponseTimeout` as a separate **5,000 ms default** for the server's SSL negotiation response, capped by `connectTimeout`. [AWS documents](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-serverless-v2-auto-pause.html) a typical Aurora Serverless v2 resume of roughly 15 seconds, potentially 30 seconds or longer after deep sleep, and recommends both `connectTimeout` and `sslResponseTimeout` above 15 seconds. The Terraform URL now explicitly sets `sslResponseTimeout=45000` ms, matching the 45-second socket-connect setting, while retaining `sslmode=verify-full`, the RDS CA bundle, `socketTimeout=120` seconds, Hikari's 60-second acquisition limit and the one-off runner's bounded task execution. These controls cover different phases; Hikari's acquisition limit is not a hard wall-clock bound on an in-progress JDBC driver operation. Whether Aurora was paused or resumed during the failed task is **unverified**; inspect RDS events, capacity metrics and task timestamps before attributing the failure to wake latency. No automatic retry or TLS weakening is introduced.

This correction changed **Terraform task-definition environment and JDBC URL only**. The application digest was reused. The following commands document the historical controlled retry; **do not run them again for Phase 5B closure**. Keep the roles, secrets, database and remote state:

```sh
export AWS_PROFILE=job-agent
aws --profile job-agent --region us-east-1 sts get-caller-identity --query '[Account,Arn]' --output table
aws --profile job-agent --region us-east-1 ecs list-tasks --cluster job-agent-p5a-batch --desired-status RUNNING
terraform -chdir=infra/terraform init -input=false -backend-config=backend.hcl
terraform -chdir=infra/terraform plan -input=false -var-file=terraform.tfvars -out=phase5b-hikari-retry.tfplan
terraform -chdir=infra/terraform show -no-color phase5b-hikari-retry.tfplan
# Only after reviewing the refreshed task definitions and obtaining separate approval:
terraform -chdir=infra/terraform apply -input=false phase5b-hikari-retry.tfplan
bash infra/bootstrap/run-one-off.sh migrate
```

The reviewed retry plan updated migration and application task definitions without replacing the database, secrets, networking or image digest. The first task's startup log alone did not prove the schema state; the successful retry subsequently reported an initially empty schema and applied V1–V13. Flyway history was not reset. The observed wake/return-to-pause cycle is recorded above; the cause of the original socket timeout remains unproven.

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

Local `linux/amd64` build and isolated PostgreSQL bootstrap tests passed with UID/GID 10001 and read-only root. Trivy 0.76.1 reported zero OS vulnerabilities for Alpine 3.24.3. Scanning the image tar reported eight Python findings from inherited metadata (`msgpack` 1.1.2, `setuptools` 70.3.0 and `urllib3` 2.7.0), but a scan of the exported runtime filesystem reported **zero vulnerabilities**; runtime inspection found neither `msgpack` nor `setuptools` and found `urllib3` 2.8.0. This discrepancy is documented rather than suppressed. The owner reports that the revised published image passed ECR basic scanning. That does not establish a comprehensive dependency audit.

### Historical revised-image publication procedure (completed by owner)

The original immutable ECR tags remain untouched. The owner subsequently published the revised bootstrap image, reviewed its ECR basic scan and ran `prepare` successfully. The commands below record the earlier publication procedure; **do not repeat it for the Hikari correction**:

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

ECR is configured to scan on push. The revised image's basic scan completed with empty finding counts before `prepare`. The historical commands above show how its digest was obtained; do not republish either image for this documentation update.

## Contract and security boundary

The existing application [entrypoint](../src/main/java/com/alexs/jobagent/JobAgentApplication.java) has **no default mode**. With no arguments it validates Flyway, reads only `COUNT(*)` from `agent_runs`, logs a safe count, closes Spring/Hikari and exits. `SPRING_PROFILES_ACTIVE=cloud-app` selects the existing validate-only Flyway strategy. The migration task omits that profile and applies the V1–V13 migrations. Neither task definition supplies `--mode=synthetic`, source import, search, dispatch, extraction or another business action. The reviewed image digest is fixed in both task definitions. JDBC uses the private cluster endpoint and `sslmode=verify-full` with the bundled us-east-1 RDS CA bundle. ECS task subnets have public IPv4 for ECR/Secrets Manager/logs, no inbound task rule and DB egress only to the private DB security group; the DB ingress permits only that task security group.

The one-off bootstrap task is separately gated by `enable_bootstrap=false` by default, uses its own 0.25-vCPU/0.5-GB Fargate definition, image digest and temporary task role. The task role can read only the RDS-managed administrator secret and the two named role secrets, and create/describe only those two role secrets. The regular application task role has no AWS actions. The ECS execution role pulls ECR, writes task logs and later retrieves only the dedicated DB password secrets for the app/migrator task definitions. The bootstrap task fetches the administrator secret **inside the task**, uses TLS verification, generates independent random passwords in memory and creates `job-agent-p5a-migration-db` and `job-agent-p5a-app-db` with JSON `password` fields. Terraform receives secret **ARNs only**, never values. The bootstrap image runs as UID 10001 with a read-only root filesystem. Do not print task environment, secret values, SQL bind values, full plan/state or task-definition secrets.

Bootstrap `prepare` refuses a pre-existing Flyway history table or any existing target role/secret. It never silently rotates or overwrites a credential. Because Secrets Manager and PostgreSQL cannot share one transaction, a crash can leave partial state; stop and review the actual roles/secrets before any rerun. Do not delete a secret or role to force a retry without reconciling which password was committed. The script stores only a SCRAM verifier in role-creation SQL, not the plaintext password. The migrator gets `CONNECT`, `USAGE` and `CREATE` on `public` and owns objects it creates. The app gets `CONNECT` and schema `USAGE`, with default DML and sequence privileges on objects the migrator creates, but no schema `CREATE` or role administration. The Flyway history table temporarily receives default table grants during migration; **do not run the app until `finalize`**. `finalize` requires migration history, revokes app writes on it, grants `SELECT`, connects as the app, reads history and performs rolled-back `CREATE TABLE` and `CREATE SCHEMA` probes that must fail for insufficient privilege. Repeating `finalize` is safe; repeating `prepare` stops for review. Validate the SQL grants and privilege evidence before any business work. These roles are separate from the managed administrator.

## Historical first-run sequence; do not repeat bootstrap

This section preserves the original first-run sequence as historical context. **All controlled Phase 5B stages have now run; none of these bootstrap or migration commands are closure steps.** Keep the existing remote backend and local ignored `backend.hcl`, `terraform.tfvars` and `activity-alert.auto.tfvars`; do not start new state. The original account/baseline commands were:

```sh
export AWS_PROFILE=job-agent
aws --profile job-agent --region us-east-1 sts get-caller-identity --query '[Account,Arn]' --output table
terraform -chdir=infra/terraform init -input=false -backend-config=backend.hcl
terraform -chdir=infra/terraform plan -input=false -var-file=terraform.tfvars -out=phase5b-baseline.tfplan
terraform -chdir=infra/terraform show -no-color phase5b-baseline.tfplan
```

Stop if the plan replaces any deployed network, database, bucket, repository, alarm or state-related resource. A clean baseline plan should have no changes before enabling new gates. Saved plans and state can expose identifiers and sensitive inputs; keep them private. Never reuse either historical Phase 5A plan.

The original `linux/amd64` application and revised bootstrap images are already published. The following first-time bootstrap commands are retained as historical procedure only; **do not execute them again**:

```sh
terraform -chdir=infra/terraform plan -input=false -var-file=terraform.tfvars -out=phase5b-bootstrap.tfplan
terraform -chdir=infra/terraform show -no-color phase5b-bootstrap.tfplan
terraform -chdir=infra/terraform apply -input=false phase5b-bootstrap.tfplan
bash infra/bootstrap/run-one-off.sh prepare
```

The runner uses the configured task subnets/security group, assigns a task public IP, prints task ARN and container exit code, and stops the task on a local timeout (15 minutes for bootstrap/app, 20 for migration). It sends no credentials in CLI arguments. If the runner loses its connection or the owner's machine stops, independently check ECS for a still-running task and stop it; the runner cannot enforce a deadline after its own process dies. An uncertain `prepare` result requires inspection of roles and secrets before any retry. No automatic secret deletion or password rotation occurs.

The following first-time runtime-enablement commands are also historical; the task definitions and secrets now exist. The migration definition has no `cloud-app` profile; app has `SPRING_PROFILES_ACTIVE=cloud-app` and neither has a batch mode argument:

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

The executed sequence stopped after the first nonzero migration exit, then resumed only after the configuration correction and fresh plan. Safe logs showed `finalize` denying application DDL and two exit-0 app checks with the same `agent_runs` count. The logs do not independently audit all tables or prove future workload behavior. Never reset Flyway history or rerun `prepare` blindly.

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

The owner observed a 0-ACU datapoint after both tasks stopped. Keep the cluster, state, image digests, role secrets and runtime task definitions for later controlled work; do not destroy the database. The temporary bootstrap role/task definition is gone, but ECS may retain stopped-task history and ECR retains the bootstrap image until separately reviewed cleanup. The activity email alert warns after approximately one hour of nonzero writer capacity; it does not stop spending. No schedule or paid source/LLM call is enabled.

## Cost and remaining permissions

This one-time sequence incurred Fargate CPU/memory time for two bootstrap tasks (`prepare` and `finalize`), the failed and successful migration tasks, and two app tasks, plus task public IPv4 time, Aurora ACU-hours while awake, logs, ECR image storage and requests. The two new database secrets have recurring [Secrets Manager](https://aws.amazon.com/secrets-manager/pricing/) storage charges of about **$0.80/month** before applicable credits; actual task/DB charges have not been measured here. The historical Phase 5A cost sketch is not a measured bill. Credits may offset eligible charges but do not guarantee payment coverage, and the existing budget/ACU emails are not hard caps. The temporary bootstrap role/policy has been removed. Owner-facing payment is actual eligible charges less applied credits; check Billing/Cost Explorer rather than assuming zero.

The owner needs ECR login/push/describe, ECS RunTask/DescribeTasks/StopTask/ListTasks, CloudWatch read, Secrets Manager **DescribeSecret only** to collect role ARNs, and IAM PassRole for the reviewed task/execution roles, in addition to the existing restricted Terraform plan/apply permissions for temporary IAM/ECS resources. The bootstrap **task role**, not the owner's CLI, receives managed-master `GetSecretValue` and narrowly scoped role-secret Create/Describe/Get. The execution role later receives only role-secret `GetSecretValue`. Verify the account's permissions and any permission boundary before execution. Do not grant the CLI direct managed-master secret retrieval for this run.

## Local verification record (2026-10-31)

The 2026-11-01 Hikari correction was checked separately: `./mvnw -B clean verify` passed 265 tests (104 unit, 161 integration), zero failures/errors/skips, including the actual-environment subprocess regression. `terraform fmt -check` passed for all tracked Terraform files; `terraform init -backend=false -input=false -lockfile=readonly` and `terraform validate` passed with AWS provider 6.71.0 using an isolated temporary `TF_DATA_DIR`, without accessing remote state. `git diff --check` passed. The broad `terraform fmt -check -recursive infra/terraform` command flagged the owner's ignored `terraform.tfvars`; it was left untouched, and the tracked-file check passed. No AWS retry, account-backed plan, cloud DB connection or application image rebuild occurred in this correction.

- `docker buildx build --platform linux/amd64 --file Dockerfile ... --load .` and the bootstrap-image equivalent passed. Both images inspected as `amd64` and UID/GID `10001:10001`; neither was published.
- `bash infra/bootstrap/test-local.sh` passed 2 tests against an isolated PostgreSQL 17.11 container, including SCRAM login, synthetic partial-secret failure with no role creation, fail-closed prepare replay, premature finalization rejection, default domain grants, migration-history read-only access, denied DDL and idempotent finalization. The bootstrap test image ran with a read-only root. This is not a test against RDS or real Secrets Manager.
- `./mvnw -B -Dit.test=BatchStartupIT verify` passed 104 unit tests and 17 PostgreSQL batch integration tests, no failures or skips. The first sandboxed attempt hit local socket/agent permission errors; a rerun with local Docker/JVM access passed. Java application code did not change, so the full integration suite was not repeated.
- Isolated `terraform init -backend=false -input=false -lockfile=readonly`, `terraform fmt -check -recursive infra/terraform`, `terraform validate`, Bash syntax, CI YAML parse and `git diff --check` passed. No account-backed plan was run.
- Trivy 0.76.1 `config --severity HIGH,CRITICAL` returned four findings: AWS-0079 (RDS AWS-managed key), AWS-0104 (broad HTTPS egress), AWS-0132 (S3 SSE-S3 rather than a customer-managed key), and AWS-0095 (unencrypted SNS alert topic). The first three were previously documented Phase 5A tradeoffs; the fourth belongs to the already merged activity alert. No finding was reported for the new bootstrap Terraform. The scan is **not clean**, and these findings require the existing cost/security review; no finding was suppressed.

The original application and revised bootstrap ECR publication, task-definition registration, secret creation, `prepare`, migration retry, `finalize`, two restricted app checks, bootstrap-access removal and return to 0 ACU are owner-reported completed steps, detailed above. Future tasks still require bounded execution and inspection of ECS after any ambiguous RunTask response. The activity alarm is a warning, not a substitute for task supervision.
