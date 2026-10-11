# Job Agent

A portfolio project for an autonomous job-search assistant built with Java 21, Spring Boot, PostgreSQL and AWS.

The planned agent discovers vacancies and employers, normalizes and deduplicates results, evaluates explicit criteria, and produces an explained shortlist. Feedback, application drafts and outcome tracking follow in later phases. Applications require human approval.

## Current status

Phase 0 (requirements and architecture) is complete. This repository currently contains project instructions and planning documents. Application code, CI, cloud deployment and Gmail/Sheet migration have not been implemented. Cloud costs are estimates, not measured bills.

## Start here

- [AGENTS.md](AGENTS.md): permanent instructions for all Work/Codex tasks.
- [MASTER_PLAN_RU.md](MASTER_PLAN_RU.md): authoritative roadmap and phase order (Russian).
- [PHASE_0.md](PHASE_0.md): requirements, source choices, proposed AWS architecture and dated cost assumptions.
- [Phase 1 task](docs/PHASE_1_TASK.md): scope and acceptance criteria for the implementation chat.
- [CAREER_EVIDENCE.md](CAREER_EVIDENCE.md): verified public-safe evidence; not a replacement for the existing AI Career Board.
- [FIRST_INCREMENT.md](FIRST_INCREMENT.md) and [PROJECT_PLAN.md](PROJECT_PLAN.md): historical supporting plans; the master plan takes precedence.

## Development workflow

One Work chat handles planning and review; a second implements scoped tasks on separate branches and prepares pull requests. PRs explain changes, rationale, test results, limitations and the linked task. The initial documentation commit bootstraps the repository; subsequent implementation goes through PRs.

The existing Google Sheet remains the sole master of the old pipeline until verified cutover. No private CVs, mailbox content, credentials or confidential commercial information belong in this public repository. Development fixtures must be synthetic. Private documents will be requested only after secured S3 storage is ready.

## Next milestone

Phase 1: a locally runnable Maven/Spring Boot foundation, Docker Compose PostgreSQL, Flyway migrations, safe configuration, relevant integration checks and GitHub Actions CI. Run commands will be added and verified in that implementation PR.
