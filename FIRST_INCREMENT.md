# First technical increment — persistence and safe processing

> Historical supporting document. Phase 0 is complete. The current scope and phase order are defined by MASTER_PLAN_RU.md and PHASE_0.md. Phase 1 delivers the runnable foundation; ingestion, idempotency and outbox belong to subsequent tasks. Earlier open region/platform decisions below are superseded by the Phase 0 baseline.

Status: proposed, 2026-10-07. No implementation has started.

## Delivery cadence

Work in small, reviewable slices. Do not treat access to the current Gmail pipeline or the existing Sheet as unresolved: both already work in the current ChatGPT workflow. The first local slice should be implemented and demonstrated in days of focused work, not held for a multiweek planning phase. Do not promise a fixed cutover date until the standalone AWS runtime's Gmail authorization path has been exercised; ChatGPT connector access does not automatically become credentials for a separately deployed Java program.

Suggested near-term sequence: (1) inspect the current Sheet schema and processing rules; (2) create the service and database migrations; (3) implement idempotent ingestion and commit-before-action behavior; (4) run focused tests and a local demonstration; (5) choose and deploy the smallest AWS runtime; (6) connect real inputs in a non-finalizing rehearsal; (7) compare imported records and switch only after the difference report is understood. Steps 1–4 are the first implementation increment.

Migration verification means a concrete comparison: number of source messages, vacancy identities, statuses, and action-list entries before and after import, plus a small sample of records. It does not mean a vague quality audit or rebuilding the existing Sheet.

## Purpose

Prove that Job Agent can retain job-search state durably and repeat processing safely. The current Google Sheet remains the live master. This increment uses synthetic messages and does not connect to Gmail, LinkedIn, paid LLMs, or AWS.

This is a technical foundation, not the first complete user-facing Job Agent. The first useful MVP also needs independent vacancy discovery from agreed sources, a deduplicated shortlist, explicit fit criteria, and a review/feedback path. Gmail pipeline migration comes later and is not the measure of product completeness.

## In scope

- Java 21 Spring Boot application, PostgreSQL in Docker Compose, schema migrations, configuration example, local run guide.
- One command or internal API that accepts a synthetic source message containing one or more vacancies.
- Store an agent run, source message ID, raw JSON payload, normalized employer and vacancy records, source links, processing result, and a pending external action. Preserve timestamps and a schema/extraction version.
- Unique source IDs and transaction boundaries prevent duplicate vacancies and actions on reruns. An external-action outbox models the future Gmail step but performs no real action.
- Focused integration tests for repeat ingestion, transaction rollback, committed-state visibility, and retry of a simulated external-action failure.

## Minimal data model

| Table | Essential fields / constraints |
| --- | --- |
| `agent_runs` | ID, agent name, started/finished time, status, error summary |
| `processed_messages` | ID, source type, source message ID (unique per source), raw payload JSONB, extraction version, state, run ID, timestamps |
| `employers` | ID, normalized name, optional canonical domain, timestamps; avoid name-only identity assumptions |
| `vacancies` | ID, employer ID, title, location, URL, normalized fingerprint, source status, timestamps |
| `vacancy_sources` | Vacancy ID, source type, source listing ID/URL, message ID, raw extracted JSONB; unique source identity |
| `external_actions` | ID, message ID, action type, idempotency key (unique), state, attempts, last error, timestamps |

The exact fingerprint and employer-matching rules are deferred to the normalization/deduplication increment. This first increment deduplicates only stable source identities. Application records, profile tables, pgvector, and LLM evaluations are later migrations.

## Done when

1. A new developer can start PostgreSQL and the service from documented commands, with no committed secrets.
2. Migrations build the schema from an empty database.
3. Replaying the same synthetic message yields one processed-message record, one set of source records, and one pending external action.
4. An injected database failure rolls back domain writes and creates no executable action.
5. A simulated external-action failure leaves committed data intact and succeeds on retry without duplicating domain records.
6. The PR records test output and a short decision note about transaction and idempotency boundaries.

## PR sequence

1. Architecture decision and cost assumptions; repository skeleton and developer instructions.
2. Database migrations and persistence integration tests.
3. Synthetic ingestion, transaction boundary, and outbox retry tests.
4. AWS deployment decision and infrastructure after a verified cost model and budget guardrail.

## Decisions needed before PR 1 is finalized

- Preferred AWS region and whether an AWS account is already available for this project.
- Monthly budget guardrail and whether public deployment is wanted at the first cloud milestone.
- Whether the shared platform should have its own repository from the start or begin as documented infrastructure in Job Agent and be extracted after the first working cloud slice.

Additional details can be resolved during implementation: exact schedule hour, Gmail ingestion mechanism, CV document fields, and retention periods. They do not block the local persistence increment.

## Career evidence

The deliverable itself is planning evidence only. Promote a LinkedIn or interview claim after merged code, tests, and measured behavior support it. The AI Career Board remains the status source of truth.
