# Phase 5A: container and reviewable AWS foundation

Status: local implementation for review; no AWS plan against an account, apply, task run or restore test. Phase 5 is incomplete.

## Scope and local checks

The Java 21 batch image is built for `linux/amd64` using digest-pinned Temurin build/runtime images, Maven Wrapper, a non-root runtime user, a minimal build context and exec-form Java entrypoint. The normal command has no mode and only checks PostgreSQL/Flyway. The `cloud-app` profile validates migrations without applying them. The separate migrator task applies migrations before normal tasks run. SIGTERM reaches Java directly; Spring closes its context and Hikari pool on ordinary exit. Abrupt termination cannot guarantee a graceful shutdown, so use ECS task stop timeouts and verify them during deployment. Process exit codes, including PARTIAL=2, are Java's exit codes.

Local commands from the repository root:

```sh
./mvnw -B clean verify
docker buildx build --platform linux/amd64 -t job-agent:phase5a-local --load .
terraform -chdir=infra/terraform init -backend=false -input=false
terraform fmt -check -recursive infra/terraform
terraform -chdir=infra/terraform validate
git diff --check
```

These checks need Docker and provider downloads but no AWS credentials. The Terraform data source for an orderable Aurora configuration is checked only in an account-backed plan. CI repeats the application tests, container build and offline Terraform checks without AWS credentials.

### Local verification record (2026-10-31)

- `./mvnw -B clean verify`: passed after allowing local sockets, Docker/Testcontainers and JVM agent attachment; 104 unit tests and 160 PostgreSQL integration tests passed, with no failures, errors or skips. The initial sandboxed run failed with 13 environment errors (socket access and Mockito agent attachment), not test assertions; it was rerun successfully with those capabilities enabled. JaCoCo reports 2/2 lines covered in `CloudFlywayConfig` through the new validate-only integration test.
- `docker buildx build --platform linux/amd64 ... --load .`: passed; image architecture `amd64`, exec-form Java entrypoint and UID/GID `10001:10001` verified by image inspection and `docker run --entrypoint id`. Only Java runtime variables appear in the image environment. The build context was about 394 KB and is allowlisted by `.dockerignore`; no local private files were supplied.
- Isolated `postgres:17.11-alpine` container smoke: first and repeat no-mode runs exited 0; missing DB exited 1 without printing the synthetic password; `cloud-app` on an empty DB exited 1 and created no Flyway history; a separate migrator role applied migrations, and a restricted application role validated the result and exited 0. Temporary containers/network were removed. The direct container PARTIAL=2 path and live ECS SIGTERM/connection cleanup remain untested; the Java exit-code unit test and exec-form entrypoint support that behavior, but deployment must verify it.
- Terraform 1.17.1 / AWS provider 6.71.0: `fmt -check`, `init -backend=false -input=false` and `validate` passed. Ruby parsed the CI YAML and exposed `build`, `terraform` and `sonar` jobs. These checks do not prove account availability, quotas, prices or a deployable plan.
- Trivy 0.76.1 Terraform scan with HIGH/CRITICAL severity returned **three findings**, not a clean scan: AWS-0079 expects an explicit KMS key ID even though `storage_encrypted=true` uses an AWS-managed RDS key; AWS-0132 recommends a customer-managed S3 key, while this low-cost baseline explicitly uses SSE-S3; AWS-0104 flags outbound HTTPS to `0.0.0.0/0`. The latter permits ECR, Secrets Manager, logs and later reviewed HTTPS calls without costly VPC endpoints/NAT, but remains a broad egress risk. The task has no inbound rule and no AWS task-role permissions. Reconsider endpoints or narrower egress before enabling live provider work. No scanner findings were suppressed.
- `git diff --check`: passed. Remote CI, account-backed `terraform plan`, AWS apply, task execution and restore verification have not run.

## Controlled deployment sequence after approval

1. Confirm account plan/service access, credits and their expiry, budget alerts, us-east-1 AZ mapping and Aurora 17.11/0 ACU ordering. Recheck prices and quotas. The owner reports USD 100 credits, Free plan ending 2027-04-30 and credit expiry 2027-10-30; these are not guarantees of eligibility or service coverage. Do not upgrade the plan automatically.
2. Create a dedicated S3 state bucket with encryption, versioning, public-access blocking, restricted IAM and S3 lockfile support. Populate a private `backend.hcl` from the synthetic example. Initialize and review a saved plan with tightly scoped AWS credentials. Keep plan/state private; never print or commit them.
3. Apply the reviewed foundation only after explicit authorization. Verify private DB, TLS, 0–2 ACU and auto-pause in the account. A failed `db.serverless` orderability query must stop the plan; do not silently switch instance classes or capacity.
4. From a controlled VPC bootstrap session, retrieve the RDS-managed master secret without putting it in shell history, create `job_agent_migrator` and `job_agent_app` LOGIN roles with separate generated passwords, then create two Secrets Manager JSON secrets with `password` keys. Grant migrator schema creation and migration ownership; grant the app only CONNECT, schema USAGE, required DML/sequence rights and read access to `flyway_schema_history`. Set migrator default table/sequence privileges for the app before migrations. Verify the app cannot create/alter schema. Remove bootstrap access afterward. Neither role uses the master credential in normal tasks. This SQL/bootstrap procedure and credential rotation remain to be tested in the account before first production task.
5. Build and push a reviewed `linux/amd64` image to ECR, record its digest, and supply digest and secret ARNs via private variables. Enable runtime, review a second plan and apply. Run the migration task manually, inspect its exit code and schema. Run one no-mode app task manually with `assignPublicIp=ENABLED` in a task subnet and the task security group. It must report database readiness, exit zero and make no paid provider call. Check failure exit handling and logs without revealing secrets.
6. Before any cloud paid LLM/search call, resolve continuity with the canonical local pilot budget/history. A new empty database cannot be used to reset or duplicate the approved USD 2 target and USD 3 authorization ceiling. S3 private-document controls must be verified before CVs are requested.

Provider API keys, if separately authorized later, should be placed in service secrets and injected only into the specific one-off task that needs them, with a scoped execution-role grant. A private search profile should be stored as an encrypted S3 object and read by a task with narrowly scoped object access only after the S3 flow and audit behavior are tested. Neither key nor profile value belongs in Terraform variables/state, image layers, logs or shell history. These integrations and IAM grants are deliberately absent from Phase 5A.

No schedule, SQS, business event handler, AWS deployment credentials in CI or live source import is included. Later Phase 5 increments cover those, an owner-approved budget notification, measured costs, backup restore drill, recovery targets and cutover. Existing Gmail/Sheets remain the old pipeline master.

## Monthly cost sketch, us-east-1, before credits

This sketch covers only the Phase 5A foundation and a hypothetical manual batch cadence, **not the complete Phase 5 architecture or its operating cost**. Illustrative 730-hour month: 13 manually started 20-minute 1-vCPU/2-GB Fargate tasks, 13 DB active periods of 25 minutes at average 0.5 ACU, 10 GB Aurora Standard storage, one million I/O requests, three secrets (RDS-managed master, migrator, app) retained for a month, 1 GB S3, 1 GB ECR and 0.1 GB ingested logs. Task duration and DB auto-pause are assumptions, not observed behavior. Rates must be verified in an account-backed calculator before apply.

| Item | Assumption | Approx. USD/month |
| --- | --- | ---: |
| Aurora compute | 5.42 h × 0.5 ACU × $0.12/ACU-h | 0.33 |
| Aurora Standard storage and I/O | 10 GB × $0.10; 1m × $0.20 | 1.20 |
| Fargate CPU/memory | 4.33 h × (1 × $0.04048 + 2 × $0.004445) | 0.21 |
| Task public IPv4 | 4.33 h × $0.005 | 0.02 |
| Three DB secrets | 3 × $0.40 plus small API use | 1.20+ |
| S3, ECR and logs | 1 GB × $0.023; 1 GB × $0.10; 0.1 GB × $0.50 | 0.17+ |
| **Phase 5A priced subtotal** | Excludes items described below and later Phase 5 resources | **about $3.13+** |

If the DB fails to pause and stays at 0.5 ACU for 730 hours, its compute alone is about **$43.80/month**, putting the same foundation subtotal around **$46.60+/month**. Scaling to 2 ACU, frequent restarts or a second writer would cost more. Temporary restore testing adds a second cluster's compute/storage, snapshot storage beyond included allowance, task/network use and possibly public IPv4; estimate its duration before the drill. Seven-day automated backup storage up to database size is generally included; retained final/manual snapshots and excess backups can be charged. An internet gateway has no standing hourly fee; data transfer, request/I/O and any later endpoints do. No NAT Gateway is included. Additional unpriced foundation usage includes the Terraform state bucket, S3 requests and versions, CloudWatch log storage/queries, Secrets Manager API calls, data transfer/egress, extra Aurora I/O and backup/snapshot storage. Future Phase 5 SQS, schedules, alarms, restore exercises, deployment automation and any added networking have **no estimate in this subtotal**.

For the **priced Phase 5A assumptions only**, the estimated final AWS payment is **$0 if all usage is credit-eligible and sufficient credits actually apply**; otherwise the payment is the uncovered actual charges. After credits expire, the estimated final payment for this same narrow scenario is **at least about $3.13/month with reliable pausing, or $46.60/month if the DB stays at 0.5 ACU**, plus the unpriced usage above. These figures are not a full operating-cost quote. Anthropic/Tavily spending is external and is **not** offset by AWS credits. Free-plan eligibility, credit application, region rates, Aurora version/auto-pause availability, Fargate quotas and service access must be checked by the owner before apply.

Pricing references: [Aurora](https://aws.amazon.com/rds/aurora/pricing/), [Fargate](https://aws.amazon.com/fargate/pricing/), [public IPv4](https://aws.amazon.com/vpc/pricing/), [Secrets Manager](https://aws.amazon.com/secrets-manager/pricing/), [RDS-managed master secrets](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-secrets-manager.html), [S3](https://aws.amazon.com/s3/pricing/), [ECR](https://aws.amazon.com/ecr/pricing/), [CloudWatch logs](https://aws.amazon.com/cloudwatch/pricing/). Rates are a planning estimate reviewed 2026-10-31, not an account quote.

## Recovery and teardown

No restore drill or measured recovery target exists. Deletion protection and Terraform `prevent_destroy` guard the cluster, writer, ECR and private bucket. A deliberate teardown needs a reviewed plan, explicit disabling of protection, confirmation that no task or pilot claim is active, export/retention decisions, a final RDS snapshot and manual cleanup of retained ECR images, bucket versions, snapshots, secrets, logs and state. Storage and secret charges continue until those resources are actually removed. Never use an unreviewed `terraform destroy` as a cleanup shortcut.
