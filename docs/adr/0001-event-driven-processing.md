# ADR 0001: Event-driven module processing

Date: 2026-10-11.
Status: Phase 2B local outbox and synthetic consumer are implemented in an uncommitted review branch. The user confirmed SQS as the later cloud transport; its configuration, workers and complete cost still need design.

## Context

Job Agent must separate discovery, persistence, evaluation and shortlist creation. A later-stage failure must not lose a saved vacancy. Start with a modular Java/Spring Boot application, PostgreSQL and batch runs; prioritize low cost, reliability and verifiable portfolio evidence.

## Implementation and remaining design

1. Keep a modular application. Modules communicate through explicit domain events and handler interfaces; extract separate services only for a demonstrated need.
2. Save a domain change and durable event in a PostgreSQL transactional outbox within one transaction. Rollback removes both writes. An in-memory Spring event sent after commit does not provide crash recovery.
3. A dispatcher reads committed records and passes them to handlers. Initial local handlers run inside the application; pending records are recovered on a later run. Network/LLM calls stay outside domain-write transactions.
4. Support at-least-once delivery, stable event_id and idempotent consumers. Do not promise exactly-once external effects. Define transaction boundaries for handler results and acknowledgement; consumer identity belongs in the deduplication key.
5. Persist attempts, safe diagnostics and next_attempt_at. After bounded retries, expose FAILED state with controlled replay. Use claim/lease handling so a worker crash does not leave an event permanently claimed.
6. Local envelope: event_id, event_type, schema_version, aggregate_id, occurred_at (UTC), run_id and minimal `vacancy_id` payload. Prefer identifiers; do not copy private documents or secrets into events. `VacancyDiscovered` v1 is emitted only on first vacancy creation and consumed by a synthetic database observer. A replay or another source input for the existing vacancy emits nothing.
7. Proposed flow: VacancyDiscovered → VacancyEvaluationRequested → VacancyEvaluated → ShortlistUpdated. Distinguish work-request commands from events describing facts. Final names/schemas are refined in the relevant PR. Handle ordering per vacancy and stale evaluation versions explicitly.
8. Phase 0 EventBridge Scheduler starts a batch run; scheduling alone does not demonstrate business event processing. Amazon SQS is the selected cloud transport. Queue type, worker costs, retry/DLQ and access controls need a separate decision before deployment. Kafka/RabbitMQ are not selected.

This is not event sourcing: PostgreSQL tables remain the source of current state; the event log does not rebuild the entire domain.

## Phase 2B local contract

`VacancyDiscovered` v1 records the first insertion of a stable vacancy. The source ingestion transaction includes the vacancy, event and successful run transition. Replaying an identical source or linking a distinct input to the existing listing does not emit again. The only consumer is a synthetic database observer; its receipt and observation commit together, followed by a separate acknowledgement transaction. The payload contains only `vacancy_id`, with no raw source input.

The dispatcher claims `PENDING` rows whose `next_attempt_at` has arrived or `CLAIMED` rows whose two-minute lease expired. PostgreSQL `FOR UPDATE SKIP LOCKED` and an update occur in one short transaction. One shared retry policy sets a maximum of three claims per cycle, including a claim lost before handler invocation. The claim transaction first moves at most one expired claim at that limit to `FAILED` with `LEASE_EXHAUSTED`; the claim query also excludes rows at the limit. Repeated worker crashes therefore cannot start a fourth handler attempt. The dispatcher performs at most 20 claim/recovery iterations per invocation. A fresh UUID claim token fences acknowledgement and failure updates; a worker releases database locks before handling. State transitions are `PENDING → CLAIMED → COMPLETED`, `CLAIMED → PENDING` on retry, `CLAIMED → FAILED` after handler exhaustion or expired final lease, and explicit `FAILED → PENDING` replay. Replay resets the cycle count while cumulative attempts remain. The pending due-time and claimed lease indexes avoid scanning the full outbox for ordinary claims. Consumer receipts use `(consumer_id, event_id)` as the unique key; if the final handler effect committed before acknowledgement was lost, the event remains `FAILED` until controlled replay, then redelivery can acknowledge it without repeating the observation. Per-invocation counters and the separate backlog snapshot are different views; the latter includes future retries, active/expired claims and terminal failures and may change concurrently.

The lease can expire while a slow handler still runs, so overlapping handler invocations remain possible. Database uniqueness and transactional receipts make this one synthetic effect idempotent. This local result does not establish exactly-once external effects. Ingestion runs have no safe ownership or lease marker yet; Phase 2B does not guess whether an old `RUNNING` row belongs to an active transaction. Its recovery is separate from implemented event-lease recovery.

## Verifiable delivery

- Phase 2: synthetic domain change and outbox in one transaction; tests for rollback, redelivery, deduplication, crash between processing and acknowledgement, competing workers and exhausted retries.
- Phases 3–4: actual vacancy/evaluation events and handlers, correlated diagnostics, observable transitions and replay without losing original inputs.
- Phase 5: cloud transport configuration and complete worker/transport estimate; verify the full path and recovery.
- External actions from the existing plan remain separate commands with their own guarantees and user approval where required. Do not create two queues for the same action.

## Consequences and evidence

Outbox introduces schema, a dispatcher, retry handling and eventual consistency between stages. User review must distinguish pending evaluation, failure and ready results.

Claim event-driven implementation for the portfolio only after the Phase 2B code and tests are merged. Local tests alone do not establish a published result.

Pattern reference: [AWS transactional outbox guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html).
