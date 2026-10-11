# Job Agent

A portfolio project for an autonomous job-search assistant built with Java 21, Spring Boot, PostgreSQL and AWS.

The planned agent discovers vacancies and employers, normalizes and deduplicates results, evaluates explicit criteria, and produces an explained shortlist. Feedback, application drafts and outcome tracking follow in later phases. Applications require human approval.

## Current status

Phase 0 is complete. Phase 1 is merged to main (PR #2, 2026-10-11); quality configuration is merged in PR #4; Phase 2A synthetic ingestion is merged in PR #5. Phase 2B local outbox delivery is prepared in this uncommitted branch for review. Normal batch startup checks PostgreSQL and Flyway migrations, then exits. Live vacancy discovery, cloud deployment and Gmail/Sheet migration are not implemented. Cloud costs are estimates, not measured bills.

## Local run

Requires Java 21, Docker Engine with Compose v2, and a shell. Maven Wrapper downloads Maven 3.9.11 on first use. Spring Boot 4.1.1 is pinned; its [official system requirements](https://docs.spring.io/spring-boot/system-requirements.html) include Java 21.

```sh
cp .env.example .env
# Edit .env and choose a local-only password. If port 5432 is occupied, set DB_PORT to a free port.
set -a; . ./.env; set +a
export DB_URL="jdbc:postgresql://localhost:${DB_PORT}/job_agent"
docker compose up -d --wait
./mvnw -B package -DskipTests
java -jar target/job-agent-0.1.0-SNAPSHOT.jar
```

The batch process prints `Database ready; agent_runs rows: 0` and exits. Flyway creates the table on the first start and validates it on later starts. The connection pool closes on exit. `DB_URL` defaults to `jdbc:postgresql://localhost:5432/job_agent`; set it when changing `DB_NAME` or `DB_PORT`. `DB_USER` and `DB_PASSWORD` come from the environment. Do not commit `.env`.

```sh
./mvnw -B clean verify        # isolated PostgreSQL tests and fresh JaCoCo coverage
docker compose down           # preserve the named database volume
docker compose down -v        # delete local development data when no longer needed
```

`verify` requires a working Docker daemon and fails if PostgreSQL cannot start. The tests use synthetic credentials and a disposable PostgreSQL 17.11 container. No H2 fallback is configured. Compose uses the same pinned PostgreSQL 17.11 image, whose fixes are listed in the [official release notes](https://www.postgresql.org/docs/17/release-17-11.html).

## Synthetic ingestion (Phase 2A)

With the same `DB_URL`, `DB_USER` and `DB_PASSWORD` environment as above, run the bundled, wholly synthetic fixture explicitly:

```sh
./mvnw -B package -DskipTests
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=synthetic
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=synthetic  # successful no-op replay
```

Normal startup never imports the fixture. The input contains a source namespace/item ID, a separate stable listing namespace/ID, an HTTPS source URL, fetch time, raw JSON, input and normalization versions, and normalized employer/vacancy fields. `source_items.raw_payload` is JSONB. Employer identity is `(source_namespace, external_id)`; vacancy identity is `(source_namespace, listing_id)`; source input identity is `(source_namespace, source_item_id)`. A name alone never identifies an employer. No cross-source fuzzy matching is performed; two sources can share a listing identity only if they deliberately use the same stable namespace and ID.

An identical source input is a successful no-op. Any changed field under an existing source-input identity, including payload or fetch time, is rejected rather than silently updated. A distinct source input may link to an existing vacancy when employer and normalized vacancy data agree. Conflicting normalized data for an existing employer/listing identity is rejected. The digest uses serialized JSON field order, so semantically equivalent JSON with reordered fields may also be rejected; versioned updates require a later explicit policy.

The ingestion service rejects an ambient transaction before creating a run. Each accepted attempt first creates an `agent_runs` row outside the ingestion transaction. One service-owned database transaction then writes source, employer, vacancy and provenance link, adds a `VacancyDiscovered` outbox event only for a newly created vacancy, and marks the run `SUCCEEDED`. Vacancy persistence and event creation join this transaction and never commit independently. On failure, the transaction rolls back those writes; a separate short write marks the run `FAILED` with a safe error code. If failure tracking also fails, the original ingestion exception is retained with the tracking error suppressed; the run may remain `RUNNING`. If the database is unavailable before run creation, no run can be recorded. A process crash before the failure write can likewise leave `RUNNING`; conservative run recovery remains unimplemented.

## Local outbox dispatch (Phase 2B, uncommitted review branch)

With the same database environment, explicitly run:

```sh
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=dispatch
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=replay-failed --event-id=<UUID>
```

`--mode=dispatch` claims at most 20 eligible events and prints per-invocation `completed`, `deferred` and `failed` counts, followed by a separate backlog snapshot: ready work, future retries, active claims, expired claims and terminal failures. The snapshot includes work not attempted by this invocation and may change while other workers run. Exit code 0 means the invocation had no newly terminal failures; deferred events remain pending for later runs. A newly exhausted event or invalid mode/replay request exits nonzero with safe diagnostics. An already terminal `FAILED` event remains visible in the backlog but does not repeatedly fail every later invocation. Normal startup neither ingests nor dispatches. The replay command accepts only a `FAILED` event and retains its event ID and consumer history.

`VacancyDiscovered` v1 means a stable vacancy was first inserted. Its envelope stores a UUID `event_id`, type/version, vacancy `aggregate_id`, UTC occurrence time, ingestion `run_id` and only `vacancy_id` in JSONB payload. Identical source replay and a distinct source input linked to the same existing vacancy produce no additional event. The local `synthetic-vacancy-observer-v1` consumer writes one durable `synthetic_vacancy_observations` row and a `(consumer_id, event_id)` receipt in one transaction. It does not evaluate vacancies or call external systems. Unsupported event types/versions fail safely.

PostgreSQL `FOR UPDATE SKIP LOCKED` claims committed eligible rows in a short transaction, using a unique token and two-minute lease. The row lock is released before the handler runs. A single retry policy allows three claims per cycle, including claims lost to a worker crash before handling. Each claim increments total and current-cycle attempts. An expired lease below the limit can be reclaimed; an expired third claim becomes visibly `FAILED` with `LEASE_EXHAUSTED` in the claim transaction and cannot produce a fourth handler attempt. Each dispatcher iteration recovers at most one exhausted claim and may claim one eligible event, within the 20-iteration invocation limit. Only the current, unexpired token can acknowledge or defer; old tokens cannot change an exhausted or replayed event. Handler failures schedule capped 1/2/4-second backoff and become `FAILED` after the third attempt. A handler commits its receipt and observation separately from delivery acknowledgement. If the final acknowledgement is lost after consumer commit, the event becomes `FAILED`; controlled replay retains its event ID and consumer receipt, then acknowledges redelivery without repeating the database observation. Replay resets only cycle attempts, preserving total attempts. This is at-least-once delivery with idempotent database effects, not exactly-once delivery or proof about external effects. Event-lease recovery is implemented; abandoned ingestion-run recovery is not, because Phase 2A run rows lack a safe ownership/lease signal.

## Coverage and SonarQube Cloud preparation

`./mvnw -B clean verify` writes JaCoCo HTML to `target/site/jacoco/index.html`, XML to `target/site/jacoco/jacoco.xml` and execution data to `target/jacoco.exec`. JaCoCo 0.8.15 instruments the Surefire and Failsafe JVMs plus each batch JVM launched by `BatchStartupIT`; their execution data is appended to the same file. Existing JVM arguments supplied through Maven's `argLine` property are retained. `clean` removes previous data. The Maven Sonar property `sonar.coverage.jacoco.xmlReportPaths` points to the XML path. No application classes are excluded, and no local coverage percentage blocks the build. The initial measurement is 11/12 application lines (91.7%) and 1/2 branches (50%); this small foundation is not a sound basis for a project-wide threshold.

The CI workflow always builds and verifies PRs without Sonar credentials. On a push to `main`, it attempts Sonar analysis **after** `clean verify` only when all required settings exist; otherwise it reports **Sonar analysis NOT CONFIGURED**. When configured, the scanner waits up to 300 seconds for the actual Quality Gate and fails the analysis step if the gate fails or the wait times out. This main-only analysis runs after merge and **does not provide a pre-merge Sonar gate**. It never exposes `SONAR_TOKEN` to PR code and does not use `pull_request_target`. PR #4 merged this configuration, but remote imported coverage, the actual Quality Gate and required GitHub-check settings remain unverified.

To connect SonarQube Cloud, the repository owner must:

1. Choose an eligible [subscription plan](https://docs.sonarsource.com/sonarqube-cloud/administering-sonarcloud/managing-subscription/subscription-plans), import/bind this GitHub repository to a SonarQube Cloud organization and project, and select CI-based analysis. Turn off Automatic Analysis under **Project Administration → Analysis Method**; [CI and automatic analysis cannot run together](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/ci-based-analysis/overview-of-integrated-cis).
2. In GitHub **Settings → Secrets and variables → Actions → Variables**, set `SONAR_HOST_URL` to the URL shown by the Sonar project, `SONAR_ORGANIZATION` to its actual organization key, and `SONAR_PROJECT_KEY` to its actual project key. No keys are assumed in this repository.
3. Create an analysis token with access to that project. Store it **only** as the GitHub Actions repository secret `SONAR_TOKEN` under **Settings → Secrets and variables → Actions → Secrets**. Do not paste the token into chat, files or command arguments.
4. After publication, inspect the next trusted `main` push workflow, Sonar project analysis, imported JaCoCo coverage and actual Quality Gate result. Configure any required GitHub check in branch protection only after that result is verified. CI-based PR analysis is deliberately not enabled here because PR code runs in the build; evaluate a safe trust model before adding token access. The [Free plan limits PR analysis to PRs targeting `main`](https://docs.sonarsource.com/sonarqube-cloud/administering-sonarcloud/managing-subscription/subscription-plans); the OSS plan offers public-project PR analysis subject to eligibility.

Proposed remote checks: start with Sonar's built-in **Sonar way** gate for new code—reliability, security and maintainability ratings A; review all new security hotspots; at least 80% new-code coverage; at most 3% duplicated new lines. These are [Sonar's published defaults](https://docs.sonarsource.com/sonarqube-cloud/standards/managing-quality-gates/introduction-to-quality-gates), not locally enforced thresholds. They focus on changes while Phase 2 adds substantial logic. Coverage and duplication conditions are ignored for fewer than 20 new lines by default, and PR gates evaluate new-code conditions only. Review the first real results before deciding whether to keep this gate; custom gates require Team/Enterprise (or an eligible OSS plan). This repository does not set the remote gate or account settings.

## Start here

- [AGENTS.md](AGENTS.md): permanent instructions for all Work/Codex tasks.
- [MASTER_PLAN.md](MASTER_PLAN.md): authoritative roadmap and phase order.
- [PHASE_0.md](PHASE_0.md): requirements, source choices, proposed AWS architecture and dated cost assumptions.
- [Phase 1 task](docs/PHASE_1_TASK.md): scope and acceptance criteria for the implementation chat.
- [Phase 2 task](docs/PHASE_2_TASK.md): merged synthetic persistence and locally prepared outbox/dispatcher.
- [CAREER_EVIDENCE.md](CAREER_EVIDENCE.md): verified public-safe evidence; not a replacement for the existing AI Career Board.
- [FIRST_INCREMENT.md](FIRST_INCREMENT.md) and [PROJECT_PLAN.md](PROJECT_PLAN.md): historical supporting plans; the master plan takes precedence.

- [Event-driven processing ADR](docs/adr/0001-event-driven-processing.md): local outbox contract and remaining cloud boundaries.

## Development workflow

The original chat plans work, prepares implementation prompts and reviews results. The implementation chat makes all source-code, configuration, documentation and translation changes, runs local checks and leaves changes uncommitted. Its handoff report lists created and changed files, the result, checks and limitations. The original chat may create a local commit after successful review. The user pushes and creates the PR using the title and description prepared by the original chat; the user also merges and deletes remote branches. AI chats must not push or create PRs without a new explicit user instruction. PRs explain changes, rationale, test results, limitations and the linked task.

The existing Google Sheet remains the sole master of the old pipeline until verified cutover. No private CVs, mailbox content, credentials or confidential commercial information belong in this public repository. Development fixtures must be synthetic. Private documents will be requested only after secured S3 storage is ready.

## Next milestone

Next: review and publish Phase 2B, then independently verify its remote CI and Sonar results. Live discovery, evaluation and cloud transport follow later phases.
