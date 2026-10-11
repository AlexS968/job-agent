# Phase 0: Job Agent development contract

Status: Phase 0 completed on 2026-10-07. The user accepted the preliminary AWS estimate. This records completed design, not application deployment or migration. LLM/search providers and their cost are selected before connection. Phase 1 was subsequently merged in PR #2 on 2026-10-11; see MASTER_PLAN.md for current state.

This is the dated design baseline. At Phase 0 completion, code and AWS resources did not exist. The working region is us-east-1 (N. Virginia); it was not proven globally cheapest. User priorities are cost/reliability; data location is unrestricted.

## Regional comparison: Virginia and Frankfurt

On 2026-10-07, Aurora Standard / ServerlessV2 / OnDemand rates were read from AWS Pricing Calculator with equivalent parameters. The earlier regional-calculator access issue was resolved; the earlier inability-to-compare message is obsolete.

| Rate / assumption | Virginia (us-east-1) | Frankfurt (eu-central-1) |
| --- | --- | --- |
| Aurora V2, USD/ACU-hour | 0.12 | 0.14 |
| Storage, USD/GB-month | 0.10 | 0.119 |
| I/O, USD per million operations | 0.20 | 0.22 |
| Compute: 13 × 25/60 × 0.5 ACU | 0.325 | 0.379 |
| Storage at 10 GB | 1.00 | 1.19 |

Compute plus storage differs by approximately USD 0.24/month for this workload. Virginia is cheaper on the verified database rates; no full comparison of every component/region was performed. Fargate was estimated from the official Virginia example; the Frankfurt Fargate unit rate was not verified. Virginia is the implementation baseline with the available estimate. No reliability advantage between regions was proven; both support the required services. [Aurora regions](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/Concepts.Aurora_Fea_Regions_DB-eng.Feature.ServerlessV2.html), [Fargate regions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate-Regions.html).

The original USD 54.08 calculator screen used `Serverless`, not V2: 1 ACU × 0.07 × 730 hours = 51.10, plus enabled Database Insights 2.28 and approximately 0.70 storage/I/O. Frankfurt V2 at 1 ACU for the full month gives USD 102.20 compute. This calculator form fixes 730 hours; its monthly total is not an estimate for 13 short batch runs. Auto-pause charges compute according to actual ACU-hours, while storage remains billed. Verify auto-pause after deployment; longer runtime, higher ACU and maintenance increase cost.

Phase 0 requirements/design support starting development. Paid deployment requires a full estimate with selected LLM/search providers and a budget alert. This document does not authorize creation of paid resources.

## Product and first useful version

The agent independently discovers vacancies and employers, persists, normalizes and deduplicates results, evaluates explicit criteria and produces an explained shortlist with source links. Gmail/LinkedIn are additional inputs; legacy migration does not replace independent search.

First useful MVP: non-Gmail discovery → PostgreSQL → criteria-based evaluation → reviewable shortlist. Feedback follows. Applications, CV generation, semantic matching, analytics and later intelligence follow MASTER_PLAN.md. Request CVs only after private S3 is ready.

## Requirements recovered from the existing workflow

The existing Master and Job Search Pipeline history were inspected. Initial direction: Java/Spring backend roles, product/in-house and international product/SaaS employers. Verify Germany hiring explicitly rather than inferring it from remote wording. Exclude consulting/client-project/intermediary offers based on verified facts. Do not invent salary, country eligibility, employment model or mandatory skills; retain unknown conditions. Do not reject Java-first roles solely because of additional tools.

Personal constraints, application history and individual decisions belong in private configuration/database, not public Git. Actual CVs are unnecessary for this step; use fictional profiles in public tests.

The current Master has five tabs: domestic employers, international remote, domestic actions, international actions and domestic gap analysis. These are employer-centric records with role, remote, compensation, employment, status, priority, provenance and next action. Do not treat every row as a separate unique vacancy. Action lists become state views rather than independent master copies. Preserve original source dates/timezones and normalize explicitly; do not alter the existing Sheet. Exact legacy tab identifiers are resolved from private source metadata during migration.

## Independent discovery sources

1. Greenhouse Job Board API: public GET endpoints for published employer vacancies/full descriptions. [Documentation](https://docs.greenhouse.io/job-board.html).
2. Lever Postings API: vacancies, location, workplace type, description and optional salary. [Documentation](https://github.com/lever/postings-api).
3. DiscoveryAdapter: broad discovery of new employers/career pages from criteria, not only a fixed employer list. SearchGateway accepts a query, request budget and returns URLs. Select the search provider before connecting a paid API. Verify discovered URLs as official employer pages/supported boards. Greenhouse/Lever are not global search engines themselves.

First source adapter: Greenhouse; second: Lever. Public availability still requires terms/rate-limit checks and retry/backoff implementation. Live endpoint smoke tests belong to adapter PRs. Phase 0 verified documentation, not working adapters.

## Proposed AWS baseline

EventBridge Scheduler → ECS Fargate RunTask → Java 21/Spring Boot container → private Aurora PostgreSQL Serverless v2. One writer, Aurora Standard, minimum 0 ACU, 5-minute auto-pause and proposed initial maximum 2 ACU. Select an engine version supporting auto-pause and later pgvector. Use local Docker PostgreSQL, Flyway and ordinary JDBC in both environments.

Fargate tasks use a public subnet and temporary public IPv4 without inbound rules, outbound HTTPS to sources/LLM and private JDBC to the database. Aurora has no public endpoint; its port is accessible only from the task security group. Batch MVP does not need a NAT Gateway, persistent load balancer, RDS Proxy or always-running ECS service. Store the image in ECR. [Fargate networking](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/networking-outbound.html).

Fargate was proposed for a standard Spring Boot container and longer discovery runs, avoiding Lambda's 15-minute run limit. Lambda may serve short later APIs/operations. Data API is unnecessary initially: retain JDBC/Flyway. Conventional RDS remains an alternative for sustained load; self-managed EC2 PostgreSQL adds operational burden for the first MVP.

Private S3: block public access, encryption, versioning and lifecycle. Proposed prefixes: documents/, cv-generated/, reports/, exports/; no personal names in keys. Use Secrets Manager for DB/OAuth. Parameter Store Standard SecureString can hold static provider credentials after KMS-key selection. Scope task IAM to specific objects/secrets. CloudWatch structured logs exclude email bodies/secrets, proposed retention 14 days and failed-run alarm. Scheduler timezone Europe/Berlin, Monday/Wednesday/Friday; agree the hour before enabling.

MVP reliability: managed storage, transactions, unique source IDs, resumable runs, bounded retries, visible errors and backup/restore drill. One writer has no instant failover to a second compute instance; recovery/rerun is acceptable for this batch product. Prove database restore before cutover. Proposed targets: seven-day backup retention and restoration within four hours; these are unverified targets, not achieved guarantees.

## Preliminary cost estimate

Region: us-east-1, USD before taxes, without Free Tier/credits. Assumptions: 13 runs of 20 minutes, 1 vCPU/2 GiB Fargate; average 0.5 ACU during each run plus five minutes until pause; conservative 10 GiB storage; three Secrets Manager secrets. This is an estimate, not a promised bill. Actual load/database maintenance may increase cost.

| Component | Calculation | USD/month |
| --- | --- | --- |
| Fargate x86 | 4.333 hours × (0.0404784 CPU + 2 × 0.004446 memory) | 0.21 |
| Aurora compute | 13 × 25/60 hours × 0.5 ACU × 0.12 | 0.33 |
| Aurora storage | 10 GiB × 0.10, based on the published Standard example | 1.00 |
| Three secrets | 3 × 0.40 | 1.20 |
| Additional costs | Reserve for S3/ECR, I/O, logs, IPv4, backups/requests; not a unit tariff | 1–3 |
| AWS total | Excludes LLM, search API, taxes and exchange rates | approximately 3.7–5.7 |

Rate examples: [Aurora](https://aws.amazon.com/rds/aurora/pricing/), [Fargate](https://aws.amazon.com/fargate/pricing/), [Secrets Manager](https://aws.amazon.com/secrets-manager/pricing/). Recalculate for different duration/ACU. At 1 ACU instead of 0.5, the corresponding compute row doubles. Close connections after the batch because pause depends on connection inactivity. [Auto-pause rules](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-serverless-v2-auto-pause.html).

Proposed AWS Budget alert: USD 10/month. It is a notification, not a hard cap or a user-approved spending limit. Estimate LLM/search separately after provider/request-budget selection. Agree the complete estimate before first paid deployment; verify regional/service rates in the deployment configuration.

Subsequent decision, 2026-10-11: the user selected SQS for cloud event transport. It was not part of the original table above. Include queue operations, polling/retries and worker costs in the deployment estimate; do not treat the old estimate as the complete event-driven architecture budget.

## Schema and first code increments

The broader technical foundation introduces agent_runs, source_items, employers, vacancies and vacancy_sources; processed_messages/external_actions precede Gmail integration. Then add evaluation_criteria, vacancy_evaluations and review_feedback. Source identity uses unique(source, source_id); external actions have unique idempotency_key. Preserve JSONB payload, URL, fetched_at and extraction_version. Domain writes and pending actions share a transaction; HTTP/LLM remain outside it. Gmail finalization follows commit; action retry does not replay domain ingestion.

The original proposed first increment included Maven/Java 21/Spring Boot, Docker PostgreSQL, Flyway, run/source/vacancy persistence, CI and a synthetic fixture. RunService manages processing; SourceAdapter returns payload; VacancyNormalizationService defines the normalized contract; VacancyRepository persists it. EvaluationGateway follows. Controllers are optional for batch runs; review APIs require protection before personal data access.

Current delivery clarification: Phase 1 delivered only the runnable foundation, agent_runs migration and startup tests. Synthetic ingestion, idempotency and rollback belong to Phase 2, following the JaCoCo/Sonar increment. The broader acceptance criteria still apply to that completed foundation sequence: clean startup/migrations, synthetic run, no repeated-input duplicates and rollback without partial domain state. Later PRs add live sources, discovery, evaluation and cloud deployment. Do not claim these features before implementation/testing.

## Migration and career evidence

Until cutover, the current Sheet is the sole old-pipeline master. New test data must not control Gmail or become a second live old-state copy. Cutover requires snapshot, provenance-aware import, employer/vacancy/status/action reconciliation, a final delta with the old writer stopped, cloud rehearsal and only then master switch. Keep rollback export and do not blindly restore snapshots to reverse external effects.

Phase 0 delivered documented requirements/design, not deployed resources. After merged PRs, verify completed scope, evidence and safe CV/LinkedIn/interview material; record it in the existing Career Board, Narrative and CAREER_EVIDENCE.md. Region/budget figures do not authorize paid resource creation.
