# Job Agent — career evidence log

This file records verifiable, public-safe material for future LinkedIn posts, CV bullets, and interview stories. Planned work is not an achievement. The AI Career Board remains the source of truth for overall program status.

## Current status (2026-10-27)

Phase 0 is complete. Phase 1 foundation, quality configuration, Phase 2A/B, Sonar cleanup, Greenhouse and Lever retrieval, bounded Tavily candidate discovery and the source-verification registry merged through PR #12. The separate main-push gates after PRs #7, #8 and #9 failed at the time; both `Build and test` and `Sonar Quality Gate` later passed for main commit `b90c3c49ca55df6e54660a102a9e49c8183306e1` in [workflow run 40479903174, attempt 2](https://github.com/AlexS968/job-agent/actions/runs/40479903174). This does not establish that all Sonar issues are closed. No broad vacancy discovery, fit evaluation, AWS deployment or legacy migration is implemented. The dated Phase 0 entries below are historical planning evidence.

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

## Verified milestone — Phase 1, 2026-10-11

- PR: https://github.com/AlexS968/job-agent/pull/2; merged 2026-10-11T14:37:13Z; merge commit 15ddb4576812e12895d2f1ed9ae24aa303f15bbc.
- Delivered: Java 21/Spring Boot 4.1.1 batch foundation, Maven Wrapper, localhost PostgreSQL 17.11 Compose, Flyway V1 agent_runs and CI.
- Evidence: reviewer clean verify passed, 2 integration tests, no failures/errors/skips; GitHub run https://github.com/AlexS968/job-agent/actions/runs/38147433818 succeeded. Tests exercise first/repeat startup and unavailable DB with nonzero exit and no password in output.
- Review improvement: child-process output redirected to temporary file to prevent pipe blockage; PostgreSQL updated; publication workflow clarified.
- Limits at Phase 1 merge: no job discovery, LLM, event-driven implementation, AWS, Sheet cutover or automated Career Board sync. JaCoCo/Sonar were added later in PR #4.
- CV draft: Built and verified a Spring Boot batch foundation with PostgreSQL migrations, Testcontainers integration tests and CI.
- LinkedIn angle: testing real database startup and failure paths before adding AI features.
- Interview story: separate JVM testing, connection closure and safe failure diagnostics; explain changes from review.
- Existing Career Board and Narrative updated manually and verified after this merge; no parallel progress tracker created.

## Verified milestone — quality configuration, 2026-10-11

- PR: https://github.com/AlexS968/job-agent/pull/4; merged 2026-10-11T15:54:04Z; merge commit `3559cada66df54430696a37959ac4d6ab0a5e89a`.
- Delivered: JaCoCo HTML/XML coverage including batch child JVMs, pinned SonarScanner configuration, and CI preparation for trusted main-push analysis with a bounded Quality Gate wait.
- Evidence: local clean verify in the implementation work reported 2 PostgreSQL integration tests and 11/12 `JobAgentApplication` lines covered. The PR's GitHub `build` check completed successfully: https://github.com/AlexS968/job-agent/actions/runs/38153139380/job/114510549098. GitHub also displayed a successful `SonarCloud Code Analysis` check on PR #4.
- Limits: Sonar's imported coverage and actual Quality Gate details were not independently inspected; the main-push scanner is after merge and does not provide a pre-merge gate. No claim of verified required GitHub checks or automatic Career Board/Narrative synchronization.
- Public-safe CV draft: Added measurable coverage and prepared gated static analysis for a Java batch application while keeping secrets out of pull-request builds.

## Verified milestone — Phase 2A synthetic ingestion, 2026-10-11

- PR: https://github.com/AlexS968/job-agent/pull/5; merged 2026-10-11T16:24:24Z; merge commit `0371b06a53fd316d5749d4dd6374996d36310db6`.
- Delivered: explicit synthetic batch ingestion into PostgreSQL with source provenance, stable employer/listing identities, raw JSONB, MapStruct conversion, idempotent replay and rollback. Run status is tracked separately from the ingestion transaction; calls inside an ambient transaction are rejected before run creation.
- Checked CI evidence: GitHub `build` check completed successfully at https://github.com/AlexS968/job-agent/actions/runs/38155160096/job/114516516502. GitHub reported a successful `SonarCloud Code Analysis` check for PR #5 at https://sonarcloud.io/dashboard?id=AlexS968_job-agent&pullRequest=5. The actual imported coverage and Quality Gate details were not independently inspected.
- Limits: synthetic input only; no live discovery, local event delivery in the merged PR, cloud transport, evaluation, AWS deployment or old-pipeline cutover. PR checks do not verify the unpublished Phase 2B work. Career Board/Narrative synchronization for this milestone is not claimed.
- CV draft: Implemented transaction-safe synthetic vacancy ingestion with PostgreSQL identity constraints, provenance and Testcontainers replay/rollback checks.
- LinkedIn angle: showing how explicit source identities and transaction ownership prevent duplicate or partial vacancy records before connecting live sources.
- Interview story: explain the choice to reject ambient transactions, commit domain writes and success together, retain failure evidence separately, and use database constraints for concurrent replay.
- Handoff for the original chat: review this evidence against PR #5, then update the existing AI Career Board and Narrative through their established workflow; verify that synchronization rather than assuming it occurred.

## Verified milestone — Phase 2B local outbox, 2026-10-11

- PR: https://github.com/AlexS968/job-agent/pull/6; merged 2026-10-11T17:30:08Z; merge commit `6f1dd5cb0afd4a1523f31e4039fb793724c106a4`.
- Delivered: transactional pending `VacancyDiscovered` events, explicit local dispatch, claim-token fencing, bounded retries and lease exhaustion, controlled replay, consumer receipts and one synthetic observation effect per vacancy.
- Evidence: PR #6 [CI build check passed](https://github.com/AlexS968/job-agent/actions/runs/38159733262/job/114529948305); [SonarCloud Code Analysis check passed](https://sonarcloud.io/dashboard?id=AlexS968_job-agent&pullRequest=6). PostgreSQL integration tests cover atomic emission, concurrency, retries, lost acknowledgement and replay. These checks do not prove external delivery or a configured cloud queue.
- Limits: no AWS/SQS transport, real vacancy evaluation or external actions. Required branch checks, Sonar imported coverage and remote gate details were not independently verified for this PR. Career Board/Narrative synchronization is not claimed.
- Public-safe CV draft: Added transaction-safe local outbox processing with bounded PostgreSQL claim recovery and idempotent consumer effects.

## Verified milestone — Sonar cleanup and pre-merge gate, 2026-10-13

- PR: https://github.com/AlexS968/job-agent/pull/7; merged 2026-10-13T20:47:23Z; merge commit `a295ed0f360f846f8f40b1b74f180023d841fcec`.
- Delivered: distinct trusted-PR `Sonar Quality Gate` CI job with tested-revision JaCoCo artifact reuse, a bounded gate wait, and corrected PostgreSQL-versus-PL/SQL analysis configuration; a Java `java:S112` finding was addressed without suppression.
- Evidence: PR #7 [Build and test](https://github.com/AlexS968/job-agent/actions/runs/38464766792/job/115528710636), [Sonar Quality Gate](https://github.com/AlexS968/job-agent/actions/runs/38464766792/job/115529447864) and [SonarCloud Code Analysis](https://sonarcloud.io/dashboard?id=AlexS968_job-agent&pullRequest=7) checks all passed. The separate [main-push build passed, but Sonar Quality Gate failed](https://github.com/AlexS968/job-agent/actions/runs/38465595112) on that merge commit. This is historical; the newer passing main run is recorded in Current status. Neither result proves closure of every open issue.
- Limits: fork/bot PRs receive no token and skip Sonar; required GitHub ruleset/check settings were not verified. Career Board/Narrative synchronization is not claimed.
- Public-safe CV draft: Prepared trusted pre-merge static analysis with coverage reuse and an explicit failing Quality Gate signal, while keeping ordinary CI independent of Sonar credentials.

## Verified milestone — first public vacancy source, 2026-10-22

- Scope: [PR #8](https://github.com/AlexS968/job-agent/pull/8) merged Greenhouse configured-board retrieval with source provenance, version-aware updates and transactional outbox events. This is one public source adapter, not broad employer discovery or fit evaluation.
- Evidence: the [PR #8 build and Sonar Quality Gate checks](https://github.com/AlexS968/job-agent/actions/runs/39769735116) succeeded. The separate [merge-commit main build succeeded while its Sonar Quality Gate failed](https://github.com/AlexS968/job-agent/actions/runs/39771947523) on commit `954deddaeabceb2561a883cf88dd026dee0f60ed`; scanner output shows JaCoCo import and `QUALITY GATE STATUS: FAILED`. The exact failing main conditions still need owner-side Sonar inspection.
- Limits: no global vacancy search, evaluation, applications, AWS delivery, old-pipeline migration or verified Career Board/Narrative synchronization. Lever retrieval was merged separately in PR #9; no broad employer discovery was delivered by PR #8.
- Public-safe CV draft: Built a read-only Greenhouse vacancy adapter with bounded retries, source-version handling, PostgreSQL provenance and atomic event creation; verified its pre-merge CI and Sonar checks while tracking a separate failing post-merge gate.

## Verified milestone — Lever source ingestion, 2026-10-22

- Scope: [PR #9](https://github.com/AlexS968/job-agent/pull/9) merged configured Lever global/EU site retrieval, observation ordering at PostgreSQL microsecond precision, source provenance and atomic vacancy/outbox writes; merge commit `14d7e80af18745b807d2c373f0ff4b129218cba3`.
- Evidence: PR #9 [Build and test](https://github.com/AlexS968/job-agent/actions/runs/39778133173/job/119822205784) and [Sonar Quality Gate](https://github.com/AlexS968/job-agent/actions/runs/39778133173/job/119823618187) passed. The separate [main-push build passed](https://github.com/AlexS968/job-agent/actions/runs/39778774983/job/119824385891), but its [Sonar Quality Gate failed](https://github.com/AlexS968/job-agent/actions/runs/39778774983/job/119825784722). The exact main gate conditions were not inspected here.
- Limits: configured sites only; no independent employer search, fit evaluation, applications, AWS delivery, old-pipeline cutover or verified Career Board/Narrative synchronization.
- Public-safe CV draft: Added a read-only Lever ingestion adapter with bounded HTTP retrieval, conservative observation ordering and transactional PostgreSQL provenance/event persistence.

## Verified milestone — bounded search candidate pilot, 2026-10-24

- Scope: [PR #10](https://github.com/AlexS968/job-agent/pull/10) merged in commit `6f7fa32`: explicit Tavily Basic search pilot, four planned queries, a persistent 100-attempt local cap, reservation before HTTP and transient unverified URL output. It did not verify employers, store the candidate URLs or import vacancies.
- Dated live observation supplied by the owner: four requests returned 34 unique `UNVERIFIED` URLs with no reported failures; 96/100 attempt slots remained **at that run**. The current database balance was not independently queried here. The sample does not establish ownership, fit, source yield or a durable retention right.
- Limits: no Brave integration, PAYGO, automated activation, evaluation, applications, AWS deployment or verified Career Board/Narrative synchronization. PR #10's separate post-merge CI and Sonar gate were not independently checked for this entry.
- Public-safe CV draft: Implemented a bounded, explicit search-candidate pilot with PostgreSQL attempt accounting and a clear trust boundary before source activation.

## Local verification — one source-to-import workflow, 2026-10-27

- Scope: On merged Phase 3C.2 code from PR #12, independently reviewed the current [Mashgin careers page](https://www.mashgin.com/careers), verified its `LEVER/global/mashgin` link twice, explicitly activated only that source in an isolated PostgreSQL database, then imported the public Lever board twice. This local exercise is uncommitted and has not been reviewed or published as a new PR.
- Evidence: each verification used one GET and retained one inactive source and one identical-evidence row. The first import made one GET, created 10 vacancies with 10 source snapshots/provenance links, 10 pending `VacancyDiscovered` events and 10 successful `CREATED` runs. The immediate repeat made one GET, returned 10 unchanged listings, added no vacancy/snapshot/event, and recorded 10 successful `UNCHANGED` runs. No board change was observed between requests. The separate Compose database and volume were removed after aggregate counts were recorded. See the README and Phase 3 task for commands, configuration and limits.
- Limits: a dated one-employer technical workflow, not broad discovery, job suitability, Germany hiring eligibility, event delivery, applications, cloud deployment or Career Board/Narrative synchronization. Public ATS listings and page contents may change. No live payloads or private data are committed.
- Remote baseline: both `Build and test` and `Sonar Quality Gate` passed for the merged main commit `b90c3c49ca55df6e54660a102a9e49c8183306e1` in [workflow run 40479903174, attempt 2](https://github.com/AlexS968/job-agent/actions/runs/40479903174). This checks the committed baseline, not the uncommitted documentation update or closure of every open Sonar issue.
