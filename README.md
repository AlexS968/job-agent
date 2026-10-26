# Job Agent

A portfolio project for an autonomous job-search assistant built with Java 21, Spring Boot, PostgreSQL and AWS.

The planned agent discovers vacancies and employers, normalizes and deduplicates results, evaluates explicit criteria, and produces an explained shortlist. Feedback, application drafts and outcome tracking follow in later phases. Applications require human approval.

## Current status

Phase 0 is complete. Phase 1, quality configuration, Phase 2A, Phase 2B, Sonar cleanup and Phase 3A Greenhouse retrieval are merged in PRs #2, #4, #5, #6, #7 and #8 respectively. Phase 3B Lever retrieval merged in PR #9; Phase 3C.1 bounded Tavily URL-candidate discovery merged in PR #10. Normal batch startup checks PostgreSQL and Flyway migrations, then exits. Verified employer discovery, evaluation, cloud deployment and Gmail/Sheet migration are not implemented. Cloud costs are estimates, not measured bills. PR #9 passed pre-merge build and Sonar checks, while its separate [main-push CI failed](https://github.com/AlexS968/job-agent/actions/runs/39778774983). A bounded Tavily live pilot made 4 requests and returned 34 unique URL candidates without errors; their ownership and usefulness remain unverified. PR #10 post-merge checks are not verified here. [The master plan](MASTER_PLAN.md#selection-criteria-and-useful-results) records the current selection and source-usefulness decisions.

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

The batch process prints the `agent_runs` row count and exits. Flyway applies and validates six migrations, then the connection pool closes. `DB_URL` defaults to `jdbc:postgresql://localhost:5432/job_agent`; set it when changing `DB_NAME` or `DB_PORT`. `DB_USER` and `DB_PASSWORD` come from the environment. Do not commit `.env`.

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

An identical source input is a successful no-op. Any changed field under an existing source-input identity, including payload or fetch time, is rejected rather than silently updated. A distinct source input may link to an existing vacancy when employer and normalized vacancy data agree. Conflicting normalized data for an existing employer/listing identity is rejected. The synthetic digest uses serialized JSON field order, so semantically equivalent JSON with reordered fields may also be rejected. Greenhouse mode has a separate versioned-update policy.

The ingestion service rejects an ambient transaction before creating a run. Each accepted attempt first creates an `agent_runs` row outside the ingestion transaction. One service-owned database transaction then writes source, employer, vacancy and provenance link, adds a `VacancyDiscovered` outbox event only for a newly created vacancy, and marks the run `SUCCEEDED`. Vacancy persistence and event creation join this transaction and never commit independently. On failure, the transaction rolls back those writes; a separate short write marks the run `FAILED` with a safe error code. If failure tracking also fails, the original ingestion exception is retained with the tracking error suppressed; the run may remain `RUNNING`. If the database is unavailable before run creation, no run can be recorded. A process crash before the failure write can likewise leave `RUNNING`; conservative run recovery remains unimplemented.

## Greenhouse board ingestion (Phase 3A, merged)

Configure up to three board tokens and verified display names as `token|name` entries separated by commas. Verify each token from the employer's public Greenhouse board URL and its board metadata before configuring it. The token, not the display name, is the employer identity. A changed display name for an existing token is rejected pending explicit review; no rename or board transfer is inferred. With the local database environment from above:

```sh
export GREENHOUSE_BOARDS='greenhouse|Greenhouse'
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=greenhouse
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=greenhouse  # unchanged refetch
```

The example board and name were checked against [Greenhouse's public board metadata](https://boards-api.greenhouse.io/v1/boards/greenhouse) on 2026-10-22. The application only calls the official `https://boards-api.greenhouse.io/v1/boards/{token}/jobs?content=true` GET endpoint. It does not submit applications, fetch application URLs or contact any other job source. Normal startup and the synthetic/dispatch modes make no Greenhouse request. A request has a 3-second connection timeout, 8-second request timeout, 9-second overall wait, 4 MB response ceiling and at most three attempts per board. At most three boards and 300 listings per board are accepted, so one invocation can issue at most nine GET requests. Transient transport failures, HTTP 429 and 500/502/503/504 retry. The cumulative retry-wait budget is four seconds per board. A valid `Retry-After` delta-seconds or HTTP-date delay is honored in full when it fits the remaining budget; a longer delay fails that board before another request. Malformed headers use a bounded local fallback. This budget covers retry waits, while request timeouts separately bound HTTP calls. HTTP 404, malformed responses, invalid board configuration and oversized responses do not retry. The [official API reference](https://docs.greenhouse.io/job-board.html) documents public unauthenticated GET access; it does not state a requests-per-second allowance, so none is assumed.

The board-scoped namespace plus the public job-post `id` identifies a vacancy. Greenhouse `internal_job_id` is not used because it identifies a different object. Each accepted source version retains its own raw JSONB, original `absolute_url`, fetch time, source `updated_at`, versions and content digest. The original URL is stored as data and may be HTTP or HTTPS, as allowed by Greenhouse examples; this mode never fetches it. Descriptions are stored as untrusted data and never rendered or logged. Missing location, compensation, remote arrangement and eligibility remain unknown. The digest sorts JSON object properties, excludes only the top-level source-version field `updated_at`, and ignores fetch time. The vacancy service decides version and content changes after the repository locks the current row, within the ingestion transaction. Equal timestamp with different content is rejected; older versions cannot overwrite newer state. A newer timestamp with unchanged content advances the current source version without another event. A genuine content change updates the current vacancy and emits `VacancyUpdated` v1; first creation emits `VacancyDiscovered` v1. Repeats emit neither. The local consumer records an event receipt for either type and creates its one observation only for discovery. Missing jobs in a later board response are not treated as closed. There is no cross-board fuzzy deduplication.

The adapter fetches and validates an entire board outside database transactions. Each listing then receives its own run and transaction: source snapshot, employer/vacancy state, provenance link, outbox event if needed, and `SUCCEEDED` transition commit together. Greenhouse run results distinguish `CREATED`, `UPDATED`, `UNCHANGED` and `STALE`; synthetic run results retain `INGESTED`/`REPLAYED`. A failed listing rolls back those writes and records `FAILED` in a separate short transaction. An ambient transaction is rejected before run creation. If the database is unavailable or the process crashes before failure tracking, a `RUNNING` row may remain; failure tracking errors are suppressed under the original processing exception. A board fetch failure has no listing run. Successful listings on a board remain committed when another listing fails. The summary prints `requests`, `fetched`, `created`, `updated`, `unchanged`, `rejected` and `failed`. These are invocation counters; fetched posts may exceed posts attempted if an unexpected processing/database failure stops the remaining work. Stale versions count as unchanged; invalid/conflicting listings count as rejected. Any rejected or failed work yields a nonzero process exit, while committed successes remain visible. No event dispatch occurs during ingestion.

The bounded live smoke test on 2026-10-22 used the `greenhouse` board (19 published posts at that moment) and a disposable local PostgreSQL database. One request imported 19 vacancies and 19 discovery events; a second request imported 0 and reported 19 unchanged. Aggregate database counts were 19 source items, 19 vacancies, 19 events and 38 successful runs. The smoke database and volume were removed. Board contents can change after this observation; no real response fixture is committed.

## Lever site ingestion (Phase 3B, merged)

Configure one or two public employer sites as `region|site|verified display name`, separated by commas. `region` is `global` or `eu`; `site` is the employer's stable Lever site identifier, not its display name. With the database environment above:

```sh
export LEVER_SITES='global|mashgin|Mashgin'
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=lever
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=lever  # unchanged refetch
```

The `mashgin` site was verified through its [public Lever posting](https://jobs.lever.co/mashgin/d0b3ec35-268b-46e2-81d6-e5e6b45a8d20) and the [official Postings API](https://github.com/lever/postings-api) on 2026-10-22. The mode only GETs `https://api.lever.co/v0/postings/{site}` or `https://api.eu.lever.co/v0/postings/{site}` with `mode=json`, `skip` and `limit`. It never follows redirects, fetches a posting/application URL, or submits an application. Normal startup and other modes do not call Lever. Each request has a 3-second connect timeout, 8-second request timeout, 9-second overall wait and 4 MB response ceiling. One site is limited to four pages of 50 listings, three attempts per page and four seconds cumulative retry wait; two sites allow at most 24 GET attempts and 400 listings per invocation. A full fourth page fails the site rather than pretending the result is complete. HTTP 429 and 500/502/503/504 and transient transport errors retry within the budget. Valid `Retry-After` delta-seconds and HTTP-date delays are honored in full or fail the site before another request; malformed values use bounded fallback. HTTP 404, malformed responses and oversized bodies do not retry. The official GET documentation does not state a rate limit or completeness guarantee; none is assumed.

Identity is `(lever, region, site, posting id)` for vacancies and `(lever-site, region, site)` for employers. Greenhouse and Lever identities never merge by name or title. Each distinct content snapshot stores its raw JSONB, original `hostedUrl`, optional `applyUrl`, source/normalization versions, digest and fetch observation time. Raw JSONB preserves Lever `description`, `opening`, `lists` and `additional` sections; normalized `description` stores the supplied description field. These fields are untrusted data and are neither rendered nor logged. Missing location, salary, remote eligibility and conditions remain unknown. A live response contained `createdAt` in raw JSON, but the official Postings API does not document a listing update timestamp. `createdAt` is not treated as a version and `source_updated_at` remains null. The observation start time is captured before the first HTTP request, then truncated down to PostgreSQL microsecond precision before source/vacancy persistence and comparison. A slower earlier fetch in an older microsecond slot is stale even if ingestion finishes later. Observations within the same microsecond slot are equal for ordering: identical content is unchanged, while conflicting content is rejected and rolled back, regardless of arrival order. Identical content also ignores fetch time and JSON object-key order; a later slot advances the watermark without a new source snapshot or event. Changed content in a later slot updates the vacancy and emits `VacancyUpdated`. This policy cannot order distinct observations inside one microsecond or recover source changes missed between fetches. Distinct snapshots remain linked as provenance. Missing listings are not treated as closed, and this is configured-board retrieval rather than global search.

HTTP fetch and full-page validation happen outside transactions. Each listing then gets its own run and transaction: snapshot, employer/vacancy, provenance, optional outbox event and `SUCCEEDED` commit atomically. A failed listing rolls back those writes and records `FAILED` separately; no run can be recorded when the database is unavailable before run creation. Ambient transactions are rejected. A fetch failure has no listing run. Previously committed listings remain when a later listing fails. The invocation reports `requests`, `fetched`, `created`, `updated`, `unchanged`, `rejected` and `failed`; stale observations count as unchanged. Any rejected or failed work produces a nonzero exit. Ingestion never dispatches events.

A bounded live smoke on 2026-10-22 used `global|mashgin|Mashgin` and a disposable localhost PostgreSQL 17.11 container. The first run made one GET and fetched/created 14 postings; the second made one GET and reported 14 unchanged. Aggregate counts were 14 source snapshots, 14 vacancies, 14 pending events and 28 successful runs. The container was removed. Live board contents may change; no response fixture was committed.

## Search candidate pilot (Phase 3C.1)

The versioned `pilot-v1` query set contains four searches for Java/Spring product employers in Germany, international SaaS employers, Germany-compatible remote roles and official Greenhouse/Lever career pages. Dry-run still checks the local database but makes no search request and needs no key:

```sh
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=search-dry-run
```

For a live run, the owner must first create a Tavily free-plan key and configure `TAVILY_API_KEY` **locally outside Git**; do not paste it into chat or put it in a URL. Keep PAYGO disabled and add no payment method. A bounded live pilot has already run; do not repeat it without a concrete reason. With the database environment configured above:

```sh
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=search-pilot
```

The command calls only Tavily Search Basic at its fixed HTTPS endpoint. It sets `auto_parameters=false`, disables answer/raw-content/images, requests usage metadata and at most ten results per query. It never fetches candidates or activates ATS boards. PostgreSQL V6 reserves one of the pilot's 100 permanent attempts before **each** request, including retries; a crash or uncertain timeout does not refund it. Concurrent workers share the cap. One invocation makes at most 12 attempts, with up to three per query. Restarts and calendar months do not reset the cap. A missing key or exhausted budget exits nonzero; failed queries also yield a nonzero exit after the summary. Provider-reported credits can differ from reserved attempts. The local cap cannot verify or control Tavily account billing: maintain the free plan and disabled PAYGO separately. `429` and server/transport failures retry within bounded delays; a valid `Retry-After` longer than the local delay budget stops the invocation before another request. Authentication and credit-limit responses do not retry.

The reservation service commits the guarded budget increment and attempt row together before HTTP; it rejects ambient transactions. Interruption during HTTP or retry waiting cancels the full invocation, preserves prior reservations and successful URLs, reports a failure and exits nonzero without another request. The output reports queries, attempts, remaining local budget, failures, cancellation and deduplicated `UNVERIFIED` URLs. Titles/snippets are transient and never persisted. V6 stores only query version/ordinal, reservation time, safe outcome, request ID and reported credits. Search locale is a preference, not proof of hiring eligibility. A search result or reachable ATS board does not establish employer ownership. Phase 3C.2 must verify official career pages and ATS ownership with SSRF-safe fetching before source registration; no candidate is currently trusted. Durable result retention needs a separate review of provider rights. See [ADR 0003](docs/adr/0003-tavily-search-pilot.md).

## Local outbox dispatch (Phase 2B)

With the same database environment, explicitly run:

```sh
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=dispatch
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=replay-failed --event-id=<UUID>
```

`--mode=dispatch` claims at most 20 eligible events and prints per-invocation `completed`, `deferred` and `failed` counts, followed by a separate backlog snapshot: ready work, future retries, active claims, expired claims and terminal failures. The snapshot includes work not attempted by this invocation and may change while other workers run. Exit code 0 means the invocation had no newly terminal failures; deferred events remain pending for later runs. A newly exhausted event or invalid mode/replay request exits nonzero with safe diagnostics. An already terminal `FAILED` event remains visible in the backlog but does not repeatedly fail every later invocation. Normal startup neither ingests nor dispatches. The replay command accepts only a `FAILED` event and retains its event ID and consumer history.

`VacancyDiscovered` v1 means a stable vacancy was first inserted. `VacancyUpdated` v1 means a newer accepted Greenhouse version or Lever observation changed listing content. Each envelope stores a UUID `event_id`, type/version, vacancy `aggregate_id`, UTC occurrence time, ingestion `run_id` and only `vacancy_id` in JSONB payload. Identical source replay and a distinct source input linked to the same existing vacancy produce no additional discovery event. The local `synthetic-vacancy-observer-v1` consumer writes a `(consumer_id, event_id)` receipt for either supported event type and one durable `synthetic_vacancy_observations` row for discovery only. It does not evaluate vacancies or call external systems. Unsupported event types/versions fail safely.

PostgreSQL `FOR UPDATE SKIP LOCKED` claims committed eligible rows in a short transaction, using a unique token and two-minute lease. The row lock is released before the handler runs. A single retry policy allows three claims per cycle, including claims lost to a worker crash before handling. Each claim increments total and current-cycle attempts. An expired lease below the limit can be reclaimed; an expired third claim becomes visibly `FAILED` with `LEASE_EXHAUSTED` in the claim transaction and cannot produce a fourth handler attempt. Each dispatcher iteration recovers at most one exhausted claim and may claim one eligible event, within the 20-iteration invocation limit. Only the current, unexpired token can acknowledge or defer; old tokens cannot change an exhausted or replayed event. Handler failures schedule capped 1/2/4-second backoff and become `FAILED` after the third attempt. A handler commits its receipt and observation separately from delivery acknowledgement. If the final acknowledgement is lost after consumer commit, the event becomes `FAILED`; controlled replay retains its event ID and consumer receipt, then acknowledges redelivery without repeating the database observation. Replay resets only cycle attempts, preserving total attempts. This is at-least-once delivery with idempotent database effects, not exactly-once delivery or proof about external effects. Event-lease recovery is implemented; abandoned ingestion-run recovery is not, because Phase 2A run rows lack a safe ownership/lease signal.

## Coverage and SonarQube Cloud preparation

PR #8's [Sonar issue list](https://sonarcloud.io/project/issues?id=AlexS968_job-agent&pullRequest=8) identified `java:S6218` in the Greenhouse HTTP response record as the single new reliability bug: generated record methods used array identity for `byte[]`. The [new-code coverage view](https://sonarcloud.io/component_measures?id=AlexS968_job-agent&pullRequest=8&metric=new_coverage&view=list) reported 77.0%, below the 80% gate. Local changes add content-based response value methods, defensive copies and risk-focused HTTP, ingestion and batch tests. `./mvnw -B clean verify` passed 73 tests locally. The later PR #8 build and Sonar Quality Gate passed, but its separate main-push gate failed after merge. This local Phase 3B work has not had remote analysis. See [Phase 3A evidence](docs/PHASE_3_TASK.md) for measured class coverage and remaining scope.

`./mvnw -B clean verify` writes JaCoCo HTML to `target/site/jacoco/index.html`, XML to `target/site/jacoco/jacoco.xml` and execution data to `target/jacoco.exec`. JaCoCo 0.8.15 instruments the Surefire and Failsafe JVMs plus each batch JVM launched by `BatchStartupIT`; their execution data is appended to the same file. Existing JVM arguments supplied through Maven's `argLine` property are retained. `clean` removes previous data. The Maven Sonar property `sonar.coverage.jacoco.xmlReportPaths` points to the XML path. No application classes are excluded, and no local coverage percentage blocks the build. Coverage must be remeasured on each increment; the original Phase 1 measurement does not describe the current application.

The `CI` workflow has separate `Build and test` and `Sonar Quality Gate` job checks. `Build and test` runs `./mvnw -B clean verify` for every PR targeting `main` and for pushes to `main`, without Sonar credentials. It checks out the PR head commit, so its tests and coverage match the revision analyzed by Sonar. It uploads only `target/classes`, `target/test-classes` and `target/site/jacoco/jacoco.xml` as a one-day artifact. `Sonar Quality Gate` depends on that successful build, checks out the same full-history revision, validates the downloaded classes/report, imports the JaCoCo XML and waits up to 300 seconds for the actual gate. A failed gate, scanner error or missing Sonar configuration fails this job. The PostgreSQL suite is not run again in the Sonar job. Main-push analysis remains enabled.

`SONAR_TOKEN` is available only to the Sonar scan step for a `main` push or a same-repository, human-authored PR targeting `main`. The current same-repository PR branches are owner-controlled; this is a trust assumption, **not** a guarantee for every same-repository contributor. Code running in that step must already be trusted, including the Maven build configuration and downloaded classes. Fork and bot-authored dependency PRs still run `Build and test`, but their `Sonar Quality Gate` job is skipped and they receive no Sonar token. A skipped Sonar job is **not** a passed analysis. The workflow does not use `pull_request_target` or grant write permissions. PRs #7 and #8 demonstrated successful pre-merge `Sonar Quality Gate` checks; their separate merge-commit main-push gates failed. The latest failure requires investigation by the owner.

To connect SonarQube Cloud, the repository owner must:

1. In SonarQube Cloud, inspect the actual main-push Quality Gate failure at merge commit `a295ed0f360f846f8f40b1b74f180023d841fcec` and its failing conditions. The [GitHub job](https://github.com/AlexS968/job-agent/actions/runs/38465595112/job/115532928669) shows that the scanner uploaded its report and then received `QUALITY GATE STATUS: FAILED`; JaCoCo XML was imported. The public issue API shows two open `java:S2589` maintainability findings, in `EmployerRepository` and `VacancyRepository`, while the public gate-status API returns no conditions. The owner must inspect the failed analysis in the Sonar UI for the exact failing condition; issue count alone does not establish gate causality. Confirm CI-based analysis is selected under **Project Administration → Analysis Method**. [Automatic Analysis and CI-based analysis cannot run together](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/ci-based-analysis/overview-of-integrated-cis). PR #7's successful analysis demonstrates PR eligibility for this project, but does not imply the main branch passes its gate.
2. The owner reports that GitHub Actions variables `SONAR_HOST_URL`, `SONAR_ORGANIZATION` and `SONAR_PROJECT_KEY`, plus repository secret `SONAR_TOKEN`, are configured. Confirm their names and access in **Settings → Secrets and variables → Actions** without revealing the token; its value must stay only in GitHub Secrets.
3. For each later PR, inspect its `Build and test` and `Sonar Quality Gate` checks, imported JaCoCo coverage and subsequent `main` analysis. A workflow definition alone does not block merge.
4. Only after the checks exist and work, configure a `main` branch protection rule or ruleset to require the GitHub Actions check runs named `Build and test` and `Sonar Quality Gate` (workflow `CI`), selecting the expected GitHub Actions app as their source if available. Review bypass permissions. [GitHub treats skipped required checks as acceptable](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches), so requiring `Sonar Quality Gate` does not enforce Sonar on fork or bot PRs; those need a separate review policy if accepted. Do not mark this rule as configured until the owner verifies it.

PR #7 sets Maven `sonar.plsql.file.suffixes=tab,pkb` for CI-based analysis so PostgreSQL migrations are not misclassified as PL/SQL. It does not provide equivalent PostgreSQL SQL static analysis. The merge-commit main-push Quality Gate failed; no claim that all former Sonar issues are resolved is supported. The owner should inspect the actual main analysis and gate conditions in SonarQube Cloud before changing rules or making closure claims.

Proposed remote checks: start with Sonar's built-in **Sonar way** gate for new code—reliability, security and maintainability ratings A; review all new security hotspots; at least 80% new-code coverage; at most 3% duplicated new lines. These are [Sonar's published defaults](https://docs.sonarsource.com/sonarqube-cloud/standards/managing-quality-gates/introduction-to-quality-gates), not locally enforced thresholds. They focus on changes while Phase 2 adds substantial logic. Coverage and duplication conditions are ignored for fewer than 20 new lines by default, and PR gates evaluate new-code conditions only. Review the first real results before deciding whether to keep this gate; custom gates require Team/Enterprise (or an eligible OSS plan). This repository does not set the remote gate or account settings.

## Start here

- [AGENTS.md](AGENTS.md): permanent instructions for all Work/Codex tasks.
- [MASTER_PLAN.md](MASTER_PLAN.md): authoritative roadmap and phase order.
- [PHASE_0.md](PHASE_0.md): requirements, source choices, proposed AWS architecture and dated cost assumptions.
- [Phase 1 task](docs/PHASE_1_TASK.md): scope and acceptance criteria for the implementation chat.
- [Phase 2 task](docs/PHASE_2_TASK.md): merged synthetic persistence and local outbox/dispatcher.
- [Phase 3 task](docs/PHASE_3_TASK.md): Greenhouse and Lever source contracts, limits and test evidence.
- [CAREER_EVIDENCE.md](CAREER_EVIDENCE.md): verified public-safe evidence; not a replacement for the existing AI Career Board.
- [FIRST_INCREMENT.md](FIRST_INCREMENT.md) and [PROJECT_PLAN.md](PROJECT_PLAN.md): historical supporting plans; the master plan takes precedence.

- [Event-driven processing ADR](docs/adr/0001-event-driven-processing.md): local outbox contract and remaining cloud boundaries.

## Development workflow

The original chat plans work, prepares implementation prompts and reviews results. The implementation chat makes all source-code, configuration, documentation and translation changes, runs local checks and leaves changes uncommitted. Its handoff report lists created and changed files, the result, checks and limitations. The original chat may create a local commit after successful review. The user pushes and creates the PR using the title and description prepared by the original chat; the user also merges and deletes remote branches. AI chats must not push or create PRs without a new explicit user instruction. PRs explain changes, rationale, test results, limitations and the linked task.

The existing Google Sheet remains the sole master of the old pipeline until verified cutover. No private CVs, mailbox content, credentials or confidential commercial information belong in this public repository. Development fixtures must be synthetic. Private documents will be requested only after secured S3 storage is ready.

## Next milestone

Next: review the local Phase 3C.1 search pilot. Its live execution requires owner-controlled Tavily free-plan configuration; Phase 3C.2 source verification is separate. The PR #9 merge-commit main Sonar failure still needs diagnosis. Evaluation and cloud transport remain later phases.
