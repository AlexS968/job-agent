# ADR 0001: Event-driven module processing

Date: 2026-10-11.
Status: event-driven architecture is an agreed direction; the mechanism below is proposed for subsequent increments. Events, outbox and handlers are not implemented. The user confirmed SQS as the cloud transport; configuration, workers and complete cost still need design.

## Context

Job Agent must separate discovery, persistence, evaluation and shortlist creation. A later-stage failure must not lose a saved vacancy. Start with a modular Java/Spring Boot application, PostgreSQL and batch runs; prioritize low cost, reliability and verifiable portfolio evidence.

## Proposed implementation

1. Keep a modular application. Modules communicate through explicit domain events and handler interfaces; extract separate services only for a demonstrated need.
2. Save a domain change and durable event in a PostgreSQL transactional outbox within one transaction. Rollback removes both writes. An in-memory Spring event sent after commit does not provide crash recovery.
3. A dispatcher reads committed records and passes them to handlers. Initial local handlers run inside the application; pending records are recovered on a later run. Network/LLM calls stay outside domain-write transactions.
4. Support at-least-once delivery, stable event_id and idempotent consumers. Do not promise exactly-once external effects. Define transaction boundaries for handler results and acknowledgement; consumer identity belongs in the deduplication key.
5. Persist attempts, safe diagnostics and next_attempt_at. After bounded retries, expose FAILED state with controlled replay. Use claim/lease handling so a worker crash does not leave an event permanently claimed.
6. Proposed envelope: event_id, event_type, schema_version, aggregate_id, occurred_at (UTC), correlation_id/run_id and minimal payload. Prefer identifiers; do not copy private documents or secrets into events.
7. Proposed flow: VacancyDiscovered → VacancyEvaluationRequested → VacancyEvaluated → ShortlistUpdated. Distinguish work-request commands from events describing facts. Final names/schemas are refined in the relevant PR. Handle ordering per vacancy and stale evaluation versions explicitly.
8. Phase 0 EventBridge Scheduler starts a batch run; scheduling alone does not demonstrate business event processing. Amazon SQS is the selected cloud transport. Queue type, worker costs, retry/DLQ and access controls need a separate decision before deployment. Kafka/RabbitMQ are not selected.

This is not event sourcing: PostgreSQL tables remain the source of current state; the event log does not rebuild the entire domain.

## Verifiable delivery

- Phase 2: synthetic domain change and outbox in one transaction; tests for rollback, redelivery, deduplication, crash between processing and acknowledgement, competing workers and exhausted retries.
- Phases 3–4: actual vacancy/evaluation events and handlers, correlated diagnostics, observable transitions and replay without losing original inputs.
- Phase 5: cloud transport configuration and complete worker/transport estimate; verify the full path and recovery.
- External actions from the existing plan remain separate commands with their own guarantees and user approval where required. Do not create two queues for the same action.

## Consequences and evidence

Outbox introduces schema, a dispatcher, retry handling and eventual consistency between stages. User review must distinguish pending evaluation, failure and ready results.

Claim event-driven implementation for the portfolio only after the relevant code and tests are merged. Current evidence supports architecture planning only.

Pattern reference: [AWS transactional outbox guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html).
