# Job Agent — career evidence log

This file records verifiable, public-safe material for future LinkedIn posts, CV bullets, and interview stories. Planned work is not an achievement. The AI Career Board remains the source of truth for overall program status.

## Current status (2026-10-07)

Planning only. No Job Agent implementation, AWS deployment, migration, or merged PR has been completed in this repository.

Phase 0 design package saved in PHASE_0.md: requirements grounded in the existing workflow and Sheet structure; independent discovery via source adapters and a separate search gateway; scheduled Fargate/private Aurora design; assumption-based AWS cost model. Evidence is documentation and official-source research only. Region baseline us-east-1 reflects cost/reliability preference; it is not a proven globally cheapest region. No implementation/deployment claim is supported yet.

Regional comparison updated: live AWS Calculator verified Aurora V2 Standard OnDemand in Virginia versus Frankfurt: 0.12 vs 0.14 USD/ACU-hour and 0.10 vs 0.119 USD/GB-month. Under the documented workload the database difference is about 0.24 USD/month. Virginia is the working choice; complete regional comparison of other services remains unverified. The initial 54.08 USD screen assumed 730 hours/month and the non-V2 Serverless option. Technical design and preliminary estimate are ready for coding; no deployment or measured operational claim is supported.

## Candidate story themes to develop after evidence exists

- Designing a reusable AWS Agent Platform for scheduled Job and House agents without an always-on laptop.
- Replacing a fragile Sheet-backed workflow with PostgreSQL while preserving a single master source through cutover.
- Enforcing commit-before-external-action processing, idempotent retries, and recoverable Gmail finalization.
- Modeling structured career data separately from private CV artifacts, with S3 storage and version provenance.
- Choosing cloud services through a region-specific cost and reliability comparison, then validating actual spend.

## Record after each merged PR

| Field | What to capture |
| --- | --- |
| PR / date | Link and merge date |
| Problem and decision | What was needed and why this approach was selected |
| Contribution | Exact implementation owned by the project |
| Evidence | Code, tests, diagrams, deployment, measured behavior, or documentation |
| Result | User or system outcome, with numbers only when measured |
| Limits | What remains unimplemented or unverified |
| Public-safe phrasing | Draft CV bullet, LinkedIn angle, and interview STAR notes |

Do not include private documents, employer-confidential information, real mailbox content, secrets, or claims about uncompleted stages.

2026-10-07: Phase 0 design accepted after the user confirmed the preliminary AWS infrastructure estimate fits. Phase 0 complete; implementation has not started. LLM/search costs remain decisions before their integration. Evidence: PHASE_0.md and explicit user acceptance in this chat. No merged PR exists yet.
