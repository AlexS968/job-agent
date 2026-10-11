# Job Agent

A portfolio project for an autonomous job-search assistant built with Java 21, Spring Boot, PostgreSQL and AWS.

The planned agent discovers vacancies and employers, normalizes and deduplicates results, evaluates explicit criteria, and produces an explained shortlist. Feedback, application drafts and outcome tracking follow in later phases. Applications require human approval.

## Current status

Phase 0 is complete. The Phase 1 foundation has passed local review and awaits CI and merge. This batch application checks PostgreSQL and Flyway migrations, then exits. Vacancy discovery, cloud deployment and Gmail/Sheet migration are not implemented. Cloud costs are estimates, not measured bills.

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
./mvnw -B verify              # includes isolated PostgreSQL integration tests via Docker
docker compose down           # preserve the named database volume
docker compose down -v        # delete local development data when no longer needed
```

`verify` requires a working Docker daemon and fails if PostgreSQL cannot start. The tests use synthetic credentials and a disposable PostgreSQL 17.11 container. No H2 fallback is configured. Compose uses the same pinned PostgreSQL 17.11 image, whose fixes are listed in the [official release notes](https://www.postgresql.org/docs/17/release-17-11.html).

## Start here

- [AGENTS.md](AGENTS.md): permanent instructions for all Work/Codex tasks.
- [MASTER_PLAN_RU.md](MASTER_PLAN_RU.md): authoritative roadmap and phase order (Russian).
- [PHASE_0.md](PHASE_0.md): requirements, source choices, proposed AWS architecture and dated cost assumptions.
- [Phase 1 task](docs/PHASE_1_TASK.md): scope and acceptance criteria for the implementation chat.
- [CAREER_EVIDENCE.md](CAREER_EVIDENCE.md): verified public-safe evidence; not a replacement for the existing AI Career Board.
- [FIRST_INCREMENT.md](FIRST_INCREMENT.md) and [PROJECT_PLAN.md](PROJECT_PLAN.md): historical supporting plans; the master plan takes precedence.

## Development workflow

The implementation chat writes code, runs local checks and leaves changes uncommitted. Its handoff report lists created and changed files, the result, checks and limitations. The original chat reviews the work, then commits, pushes and creates the PR after successful review. PRs explain changes, rationale, test results, limitations and the linked task.

The existing Google Sheet remains the sole master of the old pipeline until verified cutover. No private CVs, mailbox content, credentials or confidential commercial information belong in this public repository. Development fixtures must be synthetic. Private documents will be requested only after secured S3 storage is ready.

## Next milestone

Phase 2 will add vacancy ingestion and durable domain behavior after review and merge of the foundation changes.
