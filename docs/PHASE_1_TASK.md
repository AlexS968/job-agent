# Phase 1: Application foundation

Status: completed; PR #2 merged 2026-10-11, local checks and CI passed. The implementation chat hands off uncommitted changes to the original chat.

## Context

Repository: https://github.com/AlexS968/job-agent. Read AGENTS.md, MASTER_PLAN.md and PHASE_0.md. Phase 0 is complete. The implementation chat codes and runs local checks on codex/phase-1-foundation from current main. The original chat plans, reviews, may commit locally and prepares PR text; the user pushes and creates the PR.

## Deliverable

A locally runnable Java 21/Spring Boot application with PostgreSQL, migrations and CI. This is the autonomous agent's technical foundation, not a completed job-search agent.

## Implementation scope

1. Maven project with Maven Wrapper; select a stable supported Spring Boot version compatible with Java 21 and verify official sources.
2. Minimal modular structure following the plan. A batch run does not require a web server; do not create empty controllers/services/repositories solely for structure.
3. Docker Compose PostgreSQL with healthcheck and a dedicated persistent volume. Bind the database port to localhost only. Use environment configuration; exclude local secrets from Git. Examples contain explicitly synthetic values only.
4. Flyway and a minimal foundation migration (e.g. agent_runs). Do not implement ingestion, outbox or the full domain here. Explain the schema boundary in the handoff and subsequent PR.
5. Verifiable startup: connect, migrate/validate, exit correctly and close connections. Database unavailability produces a failure exit and useful diagnostics without secrets.
6. Necessary integration checks against real isolated PostgreSQL: empty database, repeat startup without reapplying migrations and connection failure. No H2 substitute or silently skipped Docker checks.
7. GitHub Actions CI on Java 21 for build and relevant tests including PostgreSQL integration. Use least workflow privileges and disposable public-safe test credentials.
8. README with verified setup/run/test/stop commands, requirements, versions and limits. Keep the master-plan status consistent.

## Boundaries

Leave the old Gmail/Sheet process, data and actions intact. No paid AWS resources, LLM/search APIs, live sources or private-CV requests. Phase 2 ingestion/dedup/retry/outbox, UI, embeddings and ML are outside scope. Do not repeat AWS pricing research without a concrete reason or create extra Work chats/agents.

## Acceptance criteria

- Clean startup against a fresh isolated database follows README.
- Build and necessary tests pass locally and in CI.
- Repeat startup validates migrations without recreating schema.
- Connection failure produces a nonzero exit and no password in diagnostics.
- Git contains no actual secrets, private documents or personal fixtures.
- Implementation report includes created/changed files, concise result, commands/results and limitations. The original chat prepares PR text after review; the user pushes and creates the PR.

## Handoff for review

Leave changes uncommitted. Report created/changed files, result, executed/skipped checks and limitations. Do not commit, push, create PRs or merge: the original chat may commit after review; the user publishes and merges. Preserve work and identify concrete blockers without claiming completion. Career achievements are recorded after merge; do not claim automatic Career Board integration is working.
