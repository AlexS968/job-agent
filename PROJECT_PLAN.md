# Job Agent and shared Agent Platform — working plan

> Historical supporting document. Phase 0 is complete. The current scope and phase order are defined by MASTER_PLAN.md and PHASE_0.md. Phase 1 delivers the runnable foundation; ingestion, idempotency and outbox belong to subsequent tasks. Earlier open region/platform decisions below are superseded by the Phase 0 baseline.

Updated: 2026-10-07. This document records agreed direction, not completed implementation.

## Execution plan: what we do first

**Step 0 — establish decisions, before paid AWS resources.** Read the AI Career Board and AI Career Narrative for existing commitments and status. Inventory the current Sheet's columns, identifiers, volumes, update rules, and Gmail finalization behavior without changing it. Confirm AWS region, account/budget boundary, expected message volume and runtime, recovery needs, and personal-data retention. Draft a data-flow diagram and threat model. Compare AWS database and compute choices using current regional prices, including idle costs, storage, backups, data transfer, logs, and secrets. Deliver an ADR and a cost spreadsheet with normal and failure scenarios. This is the starting step.

**Step 1 — build a safe local vertical slice.** Create the Java 21 / Spring Boot service, Docker Compose PostgreSQL, migrations, and synthetic ingestion. Implement `agent_runs`, `processed_messages`, vacancies, and an external-action outbox. Demonstrate the commit-before-action invariant and retry safety with integration tests. No live mailbox or CV is needed.

**Step 2 — deploy a minimal AWS development environment.** Add infrastructure as code for the chosen PostgreSQL, compute, S3, scheduling, IAM, secret storage, logs/alarms, and backup. Set a budget alert before deployment. Use synthetic data and run a restore drill. Keep schedules disabled until an end-to-end test succeeds.

**Step 3 — deliver the first useful Job Agent.** Add independent discovery from selected job sources and employer career pages, normalization/deduplication, explicit-criteria evaluation, a ranked shortlist with source links and reasons, and a simple feedback path. Demonstrate useful findings beyond LinkedIn alerts. Select sources based on access, terms, coverage, and the user's target roles and locations. Run this against its own development data while the current Sheet remains the live master for the old pipeline.

**Step 4 — integrate the existing Gmail pipeline and migrate as one controlled operation.** Reproduce Gmail alert handling in shadow mode. Export a complete Sheet snapshot; import it with stable source IDs and provenance; reconcile row counts, key fields, duplicate handling, and sampled decisions. Test reruns, failed DB writes, failed Gmail actions, and recovery. Freeze writes briefly, apply the final delta, verify, then switch the old pipeline to PostgreSQL as its only master. Keep a dated export and rollback instructions. Do not retire the Sheet before the cutover gate passes.

**Step 5 — expand the product.** Deepen the feedback loop, then add pgvector/embeddings, analytics, strategy and career intelligence, application assistance with explicit human approval, outcome tracking, and only then optional ML if evidence warrants it. House Agent can use the shared platform after the first agent's reliability and cost are measured, while retaining separate domain code and schedule.

**CV/document handoff reminder.** Ask the user for CVs, employment references, certificates, and other private career documents only after the secured AWS document storage and access controls are provisioned, tested, and ready for upload. Do not request these files during planning or keep them in Git. When that milestone is reached, request only the specific documents needed for the next feature and agree retention and deletion rules before upload.

**Immediate next deliverable.** A short architecture decision package: workload assumptions, AWS service comparison, region-specific cost estimate, initial data model, migration inventory, and a first PR scope. No AWS deployment or live-pipeline change is part of that deliverable.

## Goal and boundaries

Build Job Agent as a useful job-search system and a portfolio-grade Java 21 / Spring Boot / PostgreSQL / AWS project. Design its reusable cloud infrastructure so a separate House Agent and later agents can run without a continuously powered MacBook. Keep each agent's domain code and repository separate; share platform contracts and infrastructure where useful. Never put personal data, CVs, credentials, or confidential commercial-project details in Git.

**Product priority:** Job Agent must independently discover relevant vacancies across selected job sources and employer career pages, normalize and deduplicate them, assess fit against explicit career preferences, explain its ranking, and improve from user feedback. Gmail/LinkedIn alerts are only one optional input channel. Replacing the existing Sheet-backed pipeline is a compatibility and cutover task, not the product's central purpose. The first user-visible MVP must demonstrate independent discovery and a reviewable shortlist; persistence and reliability support that outcome.

The current Google Sheet `Java Job Search 2026 — Master` remains the **only master source** for the existing pipeline until the replacement is complete, migrated, verified, and explicitly cut over. Do not write partial production state to both systems. The new database can receive isolated migration rehearsals, but those copies must not drive the live pipeline or external actions.

## Target architecture to validate

EventBridge Scheduler → cloud compute → PostgreSQL system of record → committed and verified state → external actions. Use S3 for documents and generated artifacts, CloudWatch for logs/alerts, and an AWS-managed secret mechanism. Avoid a NAT Gateway unless a measured requirement justifies its standing cost.

PostgreSQL holds normalized entities and relationships. JSONB holds raw source payloads and versioned AI extraction results so they can be reprocessed. S3 holds binary files; PostgreSQL holds object keys, versions, provenance, timestamps, and relationships. The structured Career Profile is the source for tailored CV generation; each application links to the exact CV version used.

The service selection remains open: compare Aurora PostgreSQL Serverless with other AWS PostgreSQL options, and Lambda with ECS/Fargate or another suitable compute option. Compare actual minimum cost, workload fit, connection management, cold starts, networking, operational effort, backup, and restore. Verify current prices in the intended AWS region before committing. The earlier €2–5/month estimate is a hypothesis, not a budget or validated quote.

## Reliability contract

For each Gmail/LinkedIn message: ingest → analyze and evaluate all vacancies → commit database transaction → verify durable state → apply Gmail label/read/archive action. On database failure, leave Gmail unchanged. Use stable source IDs and unique constraints in `processed_messages` and domain tables for idempotency. Record `agent_runs`, processing status, attempts, errors, and the pending external action. If an external action fails after commit, retry that action safely without re-creating domain records. Treat a message as finalized only after the external action is confirmed.

## Delivery sequence

1. **Architecture and cost decision.** Document workload assumptions; compare database, compute, networking, secrets, scheduling, storage, observability, and backup options. Produce a region-specific monthly estimate with fixed, variable, and worst-case components. Decide repository boundaries between Job Agent and the shared platform.
2. **Foundation: database and persistent memory.** Provision a development environment with infrastructure as code, migrations, minimal domain schema, `agent_runs`, `processed_messages`, and restore procedure. Use synthetic data. Prove durable writes, constraints, idempotent reruns, and recovery from a failed write.
3. **Vacancy pipeline.** Add normalization and deduplication, then explicit-criteria LLM evaluation, then feedback capture. Keep raw input and extraction versions. Make an auditable evaluation record.
4. **Search and intelligence.** Add pgvector and embeddings after baseline data quality is established; then market analytics, strategy agent, and career intelligence. Measure whether each stage improves useful decisions.
5. **Applications and outcomes.** Generate tailored CV artifacts from a structured Career Profile. Add application assistance only with human approval for submissions or other external actions; then outcome tracking. Consider optional ML last, only if data and metrics justify it.
6. **Cloud schedules and migration.** Run cloud-only scheduled jobs independently by agent. Candidate Job Agent cadence: Monday, Wednesday, Friday; confirm timezone and exact hour before enabling. Import a complete snapshot from the Sheet, reconcile counts and sampled records, run isolated rehearsals, test failure and retry paths, then perform a controlled cutover. Keep Google as master until the cutover gate passes. Preserve an export and rollback procedure.

## First implementation increment (proposal)

Deliver a locally runnable Java 21 Spring Boot service backed by PostgreSQL, with schema migrations and one synthetic message-processing flow. Minimal tables: `agent_runs`, `processed_messages`, `employers`, `vacancies`, `vacancy_sources`, and `external_actions`; raw payload and extraction metadata can be JSONB fields on source records. Keep this first increment independent of live Gmail and the Google Sheet.

Done when a clean environment starts from documented commands; migrations apply; the same source message can be processed twice without duplicate vacancy or action rows; a failed DB transaction leaves no action eligible for execution; a committed record with a failed external action can be retried; focused integration tests demonstrate these behaviors; architecture and run instructions are documented without secrets.

Suggested small PRs: (1) ADRs, cost model, and repo skeleton; (2) PostgreSQL migrations and persistence tests; (3) idempotent synthetic ingestion and action-outbox tests; (4) AWS development deployment, schedule, monitoring, and restore drill; (5) import/reconciliation tooling; (6) controlled cutover after verification. Later product stages get separate PRs.

## Open decisions

- AWS region, account ownership, monthly spending guardrail, and acceptable backup/restore targets.
- Database and compute selection after current regional pricing and workload estimates.
- Exact schedule timezone and run hour; whether House Agent shares a database cluster, database, or only platform patterns.
- Gmail/LinkedIn ingestion method, API quotas, and how external actions are approved and executed.
- Data retention and deletion rules for raw messages, CVs, personal information, logs, and S3 versions.
- Whether an existing Google Sheet export can provide stable IDs and all fields needed for reconciliation.

After each merged PR, record which step it closes, evidence in code/tests/docs, and any safe CV/LinkedIn/interview material. The AI Career Board remains the source of truth for program status; this repository holds technical decisions and implementation.
