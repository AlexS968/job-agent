# Phase 2 delivery boundary

Status: Phase 2A is prepared locally for review; it is not merged. Phase 2B is planned, not implemented.

## Phase 2A — transactional synthetic ingestion

The explicit `--mode=synthetic` batch command imports one bundled synthetic input. It persists source provenance and raw JSONB, a stable employer, a stable vacancy and their link. Database unique constraints enforce source, employer and listing identities. A repeated identical source input succeeds without new domain rows; changed content under the same input identity fails. Distinct inputs may reference one listing only with agreeing normalized data. No name-only employer matching or fuzzy cross-source deduplication is claimed.

The ingestion service owns its transaction and rejects calls made within an ambient transaction before creating a run. The run row starts outside that transaction. Source, employer, vacancy, link and `SUCCEEDED` transition share one transaction; the vacancy service joins it without an independent commit. On an exception that transaction rolls back; the run is marked `FAILED` in a separate write. If failure tracking fails, the original ingestion exception is preserved with the tracking error suppressed, and the run may remain `RUNNING`. If the database is unavailable before run creation, there can be no run record. A crash can also leave a `RUNNING` row, requiring later recovery. Fixture parsing before a service call has no run row. An architecture test guards against direct cross-module repository dependencies.

Acceptance checks: empty PostgreSQL migration, first import, replay, two inputs for one listing, changed-payload conflict, concurrent replay, validation, MapStruct conversion, database failure after partial writes, rollback and retained run failure status. An ambient-transaction call must be rejected before run creation, and failure-tracking failure must preserve the original exception. The module architecture check forbids direct cross-module repository dependencies. Use `./mvnw -B clean verify` and inspect the JaCoCo report. No H2 substitute or external action is allowed.

## Phase 2B — durable outbox and dispatcher

After Phase 2A review and merge, add the transactional outbox and local dispatcher described in [ADR 0001](adr/0001-event-driven-processing.md). Define event envelopes, claim/lease recovery, at-least-once delivery, consumer deduplication, bounded retry and failed-state replay. Test rollback, redelivery, competing workers and crashes between handling and acknowledgement. Keep SQS integration and its cost/security design for the AWS phase. Phase 2A does not emit events or perform external actions.
