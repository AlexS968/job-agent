# Job Agent

A portfolio project for an autonomous job-search assistant built with Java 21, Spring Boot, PostgreSQL and AWS.

The planned agent discovers vacancies and employers, normalizes and deduplicates results, evaluates explicit criteria, and produces an explained shortlist. Feedback, application drafts and outcome tracking follow in later phases. Applications require human approval.

## Current status

Phase 0 is complete. Phase 1, quality configuration, Phase 2A, Phase 2B, Sonar cleanup and Phase 3A Greenhouse retrieval are merged in PRs #2, #4, #5, #6, #7 and #8 respectively. Phase 3B Lever retrieval merged in PR #9; Phase 3C.1 bounded Tavily URL-candidate discovery merged in PR #10; Phase 3C.2 source verification and registry merged in PR #12; Phase 4A deterministic rule evaluation merged in PR #14. Normal batch startup checks PostgreSQL and Flyway migrations, then exits. Verified association of one employer page with one ATS source and local deterministic evaluation have been exercised; broad employer discovery, model-based assessment, cloud deployment and Gmail/Sheet migration are not implemented. Cloud costs are estimates, not measured bills. PR #9 passed pre-merge build and Sonar checks, while its separate [main-push CI failed at the time](https://github.com/AlexS968/job-agent/actions/runs/39778774983); a newer passing main run is recorded below. A bounded Tavily live pilot made 4 requests and returned 34 unique URL candidates without errors; their ownership and usefulness remain unverified. PR #10 post-merge checks are not verified here. [The master plan](MASTER_PLAN.md#selection-criteria-and-useful-results) records the current selection and source-usefulness decisions.

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

The batch process prints the `agent_runs` row count and exits. Flyway applies and validates nine migrations, then the connection pool closes. `DB_URL` defaults to `jdbc:postgresql://localhost:5432/job_agent`; set it when changing `DB_NAME` or `DB_PORT`. `DB_USER` and `DB_PASSWORD` come from the environment. Do not commit `.env`.

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

The reservation service commits the guarded budget increment and attempt row together before HTTP; it rejects ambient transactions. Interruption during HTTP or retry waiting cancels the full invocation, preserves prior reservations and successful URLs, reports a failure and exits nonzero without another request. The output reports queries, attempts, remaining local budget, failures, cancellation and deduplicated `UNVERIFIED` URLs. Titles/snippets are transient and never persisted. V6 stores only query version/ordinal, reservation time, safe outcome, request ID and reported credits. Search locale is a preference, not proof of hiring eligibility. A search result or reachable ATS board does not establish employer ownership. Phase 3C.2 provides separate official-page verification before source registration; the prior Tavily candidates remain unverified. Durable result retention needs a separate review of provider rights. See [ADR 0003](docs/adr/0003-tavily-search-pilot.md).

## Source verification and registry (Phase 3C.2, merged)

The four-request Tavily pilot produced 34 transient `UNVERIFIED` URLs and left 96/100 permanent attempt slots **at that observation**. Those URLs were not saved as candidates; the current database budget must be queried before describing its current value. Verification takes explicit URLs and does not call Tavily or Brave. An owner-confirmed or independently reviewed official domain is required for automatic association. A search result, ATS URL, HTTP 200 or employer name alone cannot establish ownership.

With the local database environment above, verify at most five candidate URLs in one explicit invocation. These argument names and the manual-review path are exercised by `BatchStartupIT`; replace the synthetic examples with an owner-confirmed or independently reviewed employer domain for a real verification:

```sh
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=verify-source \
  --candidate-url=https://job-boards.greenhouse.io/acme/jobs/1 \
  --origin=owner-confirmed --official-domain=acme.example \
  --official-page-url=https://acme.example/careers --employer-name='Acme Synthetic'
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=review-sources
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=activate-source --source-id=1
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=import-active-sources
```

The example domain is synthetic and cannot produce a verified live source. Use `--origin=independently-reviewed` only after independently inspecting the employer-controlled website and its careers link; the CLI does not establish that trust root for you. `--origin=manual-candidate` with empty trust fields records `MANUAL_REVIEW` without HTTP. Review outputs registry identity/state and evidence hashes; original URLs and observed links remain in PostgreSQL for detailed review. Verification records `VERIFIED`, `MANUAL_REVIEW`, `UNSUPPORTED`, `REJECTED` or `RETRYABLE_FAILURE` with safe reasons. A supported, verified source starts inactive. Activation rechecks status and 30-day evidence freshness in a short transaction. Import rechecks eligibility, then calls the existing Greenhouse/Lever batch service outside that transaction. A source with expired evidence is deactivated as `STALE`; a later successful check of the same association restores `VERIFIED` but requires explicit activation again. A fetched page with no supported link or an unsupported platform sets `UNVERIFIED`; a strictly later supported association can recover it, also inactive. Ownership, same-domain board changes or equal-time evidence conflicts enter sticky manual review and have no automatic clearance or manual-resolution command in this increment. A conflict with another claimed employer never transfers identity silently. A per-domain decision watermark, stored at microsecond precision and locked in the short persistence transaction, prevents older positive results from overriding newer negative observations even before a source row exists. Attempt deduplication does not stop that watermark from advancing. Flyway V8 backfills the latest trusted V7 decision and deactivates sources contradicted by it; the existing V7 checksum stays unchanged. Transient fetch failures and untrusted claims do not invalidate an existing association. Search verification does not import vacancies, dispatch events or evaluate fit.

The verifier permits HTTPS port 443 only and rejects credentials, IP literals and non-public DNS answers. Apache HttpClient receives only addresses validated by its connection-time DNS resolver while retaining the original hostname for TLS verification. Automatic redirects, retries, cookies, authentication and decompression are disabled. Same-host redirects are checked individually; cross-host redirects are recorded for manual review without a request to the new host. At most five candidates, three GETs per candidate, five seconds per candidate, 256 KiB per response and 200 links per page are allowed. HTML is parsed without executing scripts or following links. Full pages and search snippets are not stored. See [ADR 0004](docs/adr/0004-source-verification-registry.md) for the trust, identity, concurrency and evidence policy. Existing manual `greenhouse` and `lever` modes remain available.

On 2026-10-27, an isolated end-to-end run used the independently reviewed [Mashgin careers page](https://www.mashgin.com/careers) and its current link to `https://jobs.lever.co/mashgin`. The application verified `LEVER/global/mashgin` twice with one GET each. Both results had the same evidence hash; the database retained one inactive registry row and one evidence row. After explicit activation of source ID 1, the first `import-active-sources` run made one Lever request, fetched and created 10 vacancies, and left 10 pending `VacancyDiscovered` events. The repeat made one request, fetched 10, reported 10 unchanged, and added no source snapshot, vacancy or event. Final isolated counts were 1 registry source, 1 verification attempt, 1 decision, 1 employer, 10 source snapshots, 10 vacancies, 10 provenance links, 10 pending discovery events and 20 successful runs (10 `CREATED`, 10 `UNCHANGED`). Counts are dated observations; the live board can change. Each source snapshot had a provenance link, and each created vacancy had a discovery event. No outbox dispatch ran.

The workflow used `DB_NAME`, `DB_USER`, `DB_PASSWORD` and a free `DB_PORT` in a private temporary env file outside Git; `DB_URL=jdbc:postgresql://127.0.0.1:${DB_PORT}/${DB_NAME}` connected the batch process. A distinct Compose project kept its volume separate from the normal `job-agent` database. With that environment set, the exercised commands were:

```sh
umask 077
cp .env.example /tmp/job-agent-source-e2e.env
# Edit the temporary file: set a new password, distinct DB_NAME and DB_USER, and a free DB_PORT.
set -a; . /tmp/job-agent-source-e2e.env; set +a
export DB_URL="jdbc:postgresql://127.0.0.1:${DB_PORT}/${DB_NAME}"
docker compose --project-name jobagent-source-e2e --env-file /tmp/job-agent-source-e2e.env up -d --wait
./mvnw -B package -DskipTests
java -jar target/job-agent-0.1.0-SNAPSHOT.jar
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=verify-source --candidate-url=https://jobs.lever.co/mashgin --origin=independently-reviewed --official-domain=www.mashgin.com --official-page-url=https://www.mashgin.com/careers --employer-name=Mashgin
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=verify-source --candidate-url=https://jobs.lever.co/mashgin --origin=independently-reviewed --official-domain=www.mashgin.com --official-page-url=https://www.mashgin.com/careers --employer-name=Mashgin
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=review-sources
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=activate-source --source-id=1
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=import-active-sources
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=import-active-sources
docker compose --project-name jobagent-source-e2e --env-file /tmp/job-agent-source-e2e.env down -v
```

The project name and file path above are examples; the actual disposable project was `jobagent-source-e2e-20261027` with `/tmp/job-agent-source-e2e-20261027-b.env`. Create a fresh private env file and choose an unused localhost port; never reuse the development database or commit the env file. SQL aggregate counts were checked before and after each stage. The local exercise establishes this one source association and replay behavior, not role suitability, Germany work eligibility, ATS completeness, event delivery, broad discovery or production operation.

## Deterministic rule evaluation (Phase 4A)

Phase 4A adds an explicit, offline rule evaluation and explained shortlist. It does not assess personal skill fit or call an LLM. Copy the fictional [profile example](docs/examples/search-profile.synthetic.json) to a private path outside Git, then edit only that external copy for a real search. Its ID/version, work country, preferred/reserve title terms, explicit exclusions, acceptable hybrid locations, EU-remote preference and optional soft salary preference form the versioned criteria. Do not commit a real profile, salary target or redirected report output. With the local database environment from **Local run** already set, a synthetic demonstration is:

```sh
cp docs/examples/search-profile.synthetic.json /tmp/job-agent-fictional-profile.json
chmod 600 /tmp/job-agent-fictional-profile.json
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=synthetic
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=evaluate-rules --profile-file=/tmp/job-agent-fictional-profile.json
java -jar target/job-agent-0.1.0-SNAPSHOT.jar --mode=shortlist --profile-file=/tmp/job-agent-fictional-profile.json --limit=20
```

The existing synthetic fixture says only `Remote`, so its expected result is `NEEDS_REVIEW` with `WORK_ELIGIBILITY_UNCONFIRMED`, not confirmed country eligibility. The private profile file is required for both commands. A batch evaluates at most 500 vacancies with pages of 100; use `--limit=100` (1–500) and the printed `last_vacancy_id` as the next `--after-id=` cursor while `more_available=true`. Shortlist output is capped at 50 entries; `--limit=20` is the default. Results are grouped as `SUITABLE` and `NEEDS_REVIEW`, with original URL, reasons, unknowns and bounded source evidence. `EXCLUDED` entries stay in evaluation history but do not appear in the shortlist. Each group orders by working-arrangement rank (country remote, EU remote, acceptable hybrid), then preferred before reserve role, then vacancy ID. The rank is an ordinal preference, not a probability. Lever's `on-site` label is normalized to `ONSITE` for configured exclusions; unknown labels remain unknown, and normalization never confirms country eligibility. Missing salary never rejects a listing; currency or period mismatches remain unknown.

V9 stores a profile ID/version/hash and append-only vacancy evaluations with a source-snapshot reference. Reusing a profile version with changed criteria fails; increment the version. Evaluation identity also contains the current vacancy/source revision and policy version (`rules-v1`). An unchanged rerun adds no evaluation, while a changed vacancy, source snapshot or version retains earlier history. Shortlist reads only current revisions for the requested profile and policy. Source statements remain untrusted: Greenhouse/Lever `remote` labels or country mentions do not establish permanent work-country permission. Positive confirmed-eligibility examples use synthetic structured evidence only. Evaluation is explicit; automatic outbox consumption, trusted live eligibility extraction, LLM assessment and cloud execution remain future work. See [ADR 0005](docs/adr/0005-rule-evaluation-baseline.md) and the [Phase 4 task](docs/PHASE_4_TASK.md).

## Anthropic evidence extraction (Phase 4B.1, local review)

The local implementation provides an explicit Haiku/Sonnet pilot opt-in; **no paid request or live pilot has been run**. V10 stores the global claim, accounting and reviewable output. The owner approved a USD 2 spending target and USD 3 maximum authorized expenditure for the entire pilot, with at most 30 primary revisions and 10 comparisons. The application uses conservative estimates for admission; they cannot guarantee the provider invoice stays below USD 3. Results do not affect `rules-v1` or the shortlist. See [ADR 0006](docs/adr/0006-anthropic-extraction-pilot.md) for pricing, recovery and residual risks.

After setting the normal local PostgreSQL environment, safe offline modes are:

```sh
./mvnw -B clean verify
./mvnw -B spring-boot:run -Dspring-boot.run.arguments="--mode=extract-status"
./mvnw -B spring-boot:run -Dspring-boot.run.arguments="--mode=extract-plan --vacancy-id=1 --comparison=false"
```

The planning mode needs an existing vacancy ID and reports only identifiers, revision and selected field names. It does not log vacancy text. `extract-status` also accepts `--vacancy-id=ID --comparison=false`; it shows accounted/observed spending, active work and halt reason without logging claims. Normal startup, planning, status and tests never contact Anthropic. For a separately reviewed live pilot, configure `ANTHROPIC_API_KEY` outside the repository and run `--mode=extract-primary --vacancy-id=ID --live-confirm=yes --pricing-reviewed-on=YYYY-MM-DD`; `extract-compare` uses the same options after a successful primary result. The reviewed date must equal the pinned price date and the local date. No automatic fallback, top-up or expansion is enabled. Oversized input is rejected before HTTP; an oversized response is uncertain because reading stops after submission, so its full reservation and global claim remain. An uncertain request blocks all workers even after lease expiry. After confirming that no provider request is in flight and reviewing billing, `--mode=extract-recover --work-id=ID --confirm-no-inflight=yes --billing-reviewed=yes` can release the global slot after 60 seconds; it retains the reservation and does not replay the request. Replay requires a separate explicit `--replay-uncertain=yes` on the live command. An over-estimate observation halts further admission until a separately reviewed intervention; recovery alone does not clear the halt. No exact per-pilot provider hard cap was verified or configured. Provider workspace monthly limits, if available to the owner, are separate controls with possible billing lag and other activity. Remote CI/Sonar for this local change remain unverified until publication, and Phase 4 is incomplete.

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

PR #8's [Sonar issue list](https://sonarcloud.io/project/issues?id=AlexS968_job-agent&pullRequest=8) identified `java:S6218` in the Greenhouse HTTP response record as the single new reliability bug: generated record methods used array identity for `byte[]`. The [new-code coverage view](https://sonarcloud.io/component_measures?id=AlexS968_job-agent&pullRequest=8&metric=new_coverage&view=list) reported 77.0%, below the 80% gate. The subsequent fix added content-based response value methods, defensive copies and risk-focused HTTP, ingestion and batch tests. Its local `./mvnw -B clean verify` passed 73 tests. The later PR #8 build and Sonar Quality Gate passed, but its separate main-push gate failed after merge; a newer main run is reported below. See [Phase 3A evidence](docs/PHASE_3_TASK.md) for measured class coverage and remaining scope.

`./mvnw -B clean verify` writes JaCoCo HTML to `target/site/jacoco/index.html`, XML to `target/site/jacoco/jacoco.xml` and execution data to `target/jacoco.exec`. JaCoCo 0.8.15 instruments the Surefire and Failsafe JVMs plus each batch JVM launched by `BatchStartupIT`; their execution data is appended to the same file. Existing JVM arguments supplied through Maven's `argLine` property are retained. `clean` removes previous data. The Maven Sonar property `sonar.coverage.jacoco.xmlReportPaths` points to the XML path. No application classes are excluded, and no local coverage percentage blocks the build. Coverage must be remeasured on each increment; the original Phase 1 measurement does not describe the current application.

The `CI` workflow has separate `Build and test` and `Sonar Quality Gate` job checks. `Build and test` runs `./mvnw -B clean verify` for every PR targeting `main` and for pushes to `main`, without Sonar credentials. It checks out the PR head commit, so its tests and coverage match the revision analyzed by Sonar. It uploads only `target/classes`, `target/test-classes` and `target/site/jacoco/jacoco.xml` as a one-day artifact. `Sonar Quality Gate` depends on that successful build, checks out the same full-history revision, validates the downloaded classes/report, imports the JaCoCo XML and waits up to 300 seconds for the actual gate. A failed gate, scanner error or missing Sonar configuration fails this job. The PostgreSQL suite is not run again in the Sonar job. Main-push analysis remains enabled.

`SONAR_TOKEN` is available only to the Sonar scan step for a `main` push or a same-repository, human-authored PR targeting `main`. The current same-repository PR branches are owner-controlled; this is a trust assumption, **not** a guarantee for every same-repository contributor. Code running in that step must already be trusted, including the Maven build configuration and downloaded classes. Fork and bot-authored dependency PRs still run `Build and test`, but their `Sonar Quality Gate` job is skipped and they receive no Sonar token. A skipped Sonar job is **not** a passed analysis. The workflow does not use `pull_request_target` or grant write permissions. PRs #7 and #8 demonstrated successful pre-merge `Sonar Quality Gate` checks; their separate merge-commit main-push gates failed at the time. For main commit `b90c3c49ca55df6e54660a102a9e49c8183306e1`, both `Build and test` and `Sonar Quality Gate` passed in [workflow run 40479903174, attempt 2](https://github.com/AlexS968/job-agent/actions/runs/40479903174). A passing gate does not establish that every open Sonar issue is resolved.

To maintain and review SonarQube Cloud analysis, the repository owner should:

1. Treat the earlier main-push failure at merge commit `a295ed0f360f846f8f40b1b74f180023d841fcec` as historical evidence: the [GitHub job](https://github.com/AlexS968/job-agent/actions/runs/38465595112/job/115532928669) imported JaCoCo XML, uploaded its report and received `QUALITY GATE STATUS: FAILED`. The exact conditions of that historical analysis were not independently captured here. The newer main [run 40479903174, attempt 2](https://github.com/AlexS968/job-agent/actions/runs/40479903174) passed both named jobs. Continue inspecting actual Sonar issues and gate conditions for later revisions; a passing gate is not proof that all issues are closed. CI-based analysis should remain selected under **Project Administration → Analysis Method** because [Automatic Analysis and CI-based analysis cannot run together](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/ci-based-analysis/overview-of-integrated-cis).
2. The owner reports that GitHub Actions variables `SONAR_HOST_URL`, `SONAR_ORGANIZATION` and `SONAR_PROJECT_KEY`, plus repository secret `SONAR_TOKEN`, are configured. Confirm their names and access in **Settings → Secrets and variables → Actions** without revealing the token; its value must stay only in GitHub Secrets.
3. For each later PR, inspect its `Build and test` and `Sonar Quality Gate` checks, imported JaCoCo coverage and subsequent `main` analysis. A workflow definition alone does not block merge.
4. Only after the checks exist and work, configure a `main` branch protection rule or ruleset to require the GitHub Actions check runs named `Build and test` and `Sonar Quality Gate` (workflow `CI`), selecting the expected GitHub Actions app as their source if available. Review bypass permissions. [GitHub treats skipped required checks as acceptable](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches), so requiring `Sonar Quality Gate` does not enforce Sonar on fork or bot PRs; those need a separate review policy if accepted. Do not mark this rule as configured until the owner verifies it.

PR #7 sets Maven `sonar.plsql.file.suffixes=tab,pkb` for CI-based analysis so PostgreSQL migrations are not misclassified as PL/SQL. It does not provide equivalent PostgreSQL SQL static analysis. Earlier merge-commit main-push Quality Gates failed; the named jobs passed on main commit `b90c3c49ca55df6e54660a102a9e49c8183306e1` in attempt 2 of the run linked above. Do not infer that all former Sonar issues are resolved from this gate result.

Proposed remote checks: start with Sonar's built-in **Sonar way** gate for new code—reliability, security and maintainability ratings A; review all new security hotspots; at least 80% new-code coverage; at most 3% duplicated new lines. These are [Sonar's published defaults](https://docs.sonarsource.com/sonarqube-cloud/standards/managing-quality-gates/introduction-to-quality-gates), not locally enforced thresholds. They focus on changes while Phase 2 adds substantial logic. Coverage and duplication conditions are ignored for fewer than 20 new lines by default, and PR gates evaluate new-code conditions only. Review the first real results before deciding whether to keep this gate; custom gates require Team/Enterprise (or an eligible OSS plan). This repository does not set the remote gate or account settings.

## Start here

- [AGENTS.md](AGENTS.md): permanent instructions for all Work/Codex tasks.
- [MASTER_PLAN.md](MASTER_PLAN.md): authoritative roadmap and phase order.
- [PHASE_0.md](PHASE_0.md): requirements, source choices, proposed AWS architecture and dated cost assumptions.
- [Phase 1 task](docs/PHASE_1_TASK.md): scope and acceptance criteria for the implementation chat.
- [Phase 2 task](docs/PHASE_2_TASK.md): merged synthetic persistence and local outbox/dispatcher.
- [Phase 3 task](docs/PHASE_3_TASK.md): Greenhouse and Lever source contracts, limits and test evidence.
- [Phase 4 task](docs/PHASE_4_TASK.md): deterministic evaluation baseline and remaining scope.
- [CAREER_EVIDENCE.md](CAREER_EVIDENCE.md): verified public-safe evidence; not a replacement for the existing AI Career Board.
- [FIRST_INCREMENT.md](FIRST_INCREMENT.md) and [PROJECT_PLAN.md](PROJECT_PLAN.md): historical supporting plans; the master plan takes precedence.

- [Event-driven processing ADR](docs/adr/0001-event-driven-processing.md): local outbox contract and remaining cloud boundaries.

## Development workflow

The original chat plans work, prepares implementation prompts and reviews results. The implementation chat makes all source-code, configuration, documentation and translation changes, runs local checks and leaves changes uncommitted. Its handoff report lists created and changed files, the result, checks and limitations. The original chat may create a local commit after successful review. The user pushes and creates the PR using the title and description prepared by the original chat; the user also merges and deletes remote branches. AI chats must not push or create PRs without a new explicit user instruction. PRs explain changes, rationale, test results, limitations and the linked task.

The existing Google Sheet remains the sole master of the old pipeline until verified cutover. No private CVs, mailbox content, credentials or confidential commercial information belong in this public repository. Development fixtures must be synthetic. Private documents will be requested only after secured S3 storage is ready.

## Next milestone

Next: review the dated source-to-import evidence and the remaining Phase 3 discovery gaps. The latest verified main run passed `Build and test` and `Sonar Quality Gate`; historical failures remain documented above. Evaluation and cloud transport remain later phases.
