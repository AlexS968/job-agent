# Job Agent: Complete delivery plan

Status: 2026-10-29. Phase 4A deterministic rule evaluation is merged in PR #14; Phase 4B.1 Anthropic evidence extraction is merged in PR #15. Phase 4B.2 made two owner-run Haiku requests for verified Solaris vacancy 15. Both failed strict validation; the second retained response identified a salary-period contract mismatch and passes revised validation offline. Historical failures and spending remain recorded; no further paid request was made in the diagnostic task. The owner approved a USD 2 target and USD 3 maximum authorized pilot expenditure; estimates do not guarantee provider billing. Phase 3 and Phase 4 are incomplete. This is the authoritative development entry point. `PROJECT_PLAN.md` and `FIRST_INCREMENT.md` contain supporting detail; this plan takes precedence in a conflict.

Phase 0 completed on 2026-10-07 when the user accepted the preliminary AWS estimate. `PHASE_0.md` records requirements from the existing workflow, source documentation checks, the proposed Fargate/Aurora design and us-east-1 estimate with explicit assumptions. There is no EU-only region requirement: cost and reliability take priority over data location. The estimate excludes later LLM/search-provider choices and does not authorize paid deployment.

## 1. Product

Job Agent is an autonomous cloud job-search application. Scheduled runs discover vacancies from multiple sources, persist and deduplicate them, evaluate explicit criteria, present an explained shortlist and capture user decisions. Later features help draft applications and track outcomes. Applications and other actions requiring human judgment need explicit user approval.

The current Gmail → Google Sheet pipeline remains active until cutover. Migration is one Job Agent integration, not its product goal. The new agent must find useful vacancies independently of LinkedIn alerts.

## 2. Component responsibilities

- **Java 21 / Spring Boot:** discovery, normalization, evaluation, recommendations, actions and APIs. The same application code runs locally during development and on AWS in operation.
- **PostgreSQL:** the new system's master after cutover, storing vacancies, employers, evaluations, decisions, applications and runs. JSONB stores raw source responses and versioned AI extraction.
- **S3:** private CVs/documents and generated CV versions. Database records hold object references and version relationships; files never enter Git.
- **EventBridge Scheduler:** cloud scheduling without an active MacBook. Initial candidate: Monday/Wednesday/Friday; agree the hour before enabling.
- **AWS compute:** runs the Java application. Phase 0 proposes ECS/Fargate for the batch workload; validate runtime, networking and complete deployment cost before creating paid resources. Lambda remains a candidate for short later operations.
- **CloudWatch, IAM, secret storage and backups:** observability, limited privileges, credentials and recovery.
- **Google Cloud OAuth registration:** grants the Java application Gmail access. It does not host the application/database. Existing ChatGPT Gmail access belongs to the old workflow; the AWS application needs its own authorization.

A future House Agent may reuse platform patterns but keeps its domain logic and schedule separate. Deliver a useful Job Agent before extracting a shared platform.

## 3. One agent run

1. Assign `run_id` and select enabled sources.
2. Source adapters discover vacancies through verified official APIs/feeds and employer career pages. Gmail/LinkedIn alerts are additional inputs. Do not assume arbitrary websites permit automated collection.
3. Persist raw payload, URL, discovery time and adapter version.
4. Normalize role, employer, location, working arrangement and requirements. Deduplicate through stable IDs/URLs, then a verified fingerprint.
5. Rules reject clearly unsuitable vacancies. A cloud LLM evaluates the rest against explicit criteria, preserving scores, arguments, model/prompt version and source facts. Failed evaluations remain available for retry.
6. Present a shortlist with links, reasons for/against, unknowns and recommended next actions. Record interested/not interested feedback and reasons.
7. Update criteria/prompts in a controlled, versioned way and compare quality. Do not submit applications autonomously.
8. For Gmail messages, finish processing and commit PostgreSQL state before labeling, marking read or archiving. Persist failed Gmail actions for safe retry.

### Selection criteria and useful results

Phase 4 should use a versioned, privately stored search profile. The initial role families are senior Java/Spring backend, backend/integration engineering, application modernization and senior software engineering; application management or technical operations is a reserve category when experience fits. These are configurable search preferences, not permanent source-code constants. A future owner-facing control may edit roles, location and salary after the first useful shortlist is proven; it does not add a new phase now.

Rank confirmed fully remote work in Germany first, then remote EU roles that explicitly permit permanent work from the user's country, with geographically acceptable hybrid roles as a reserve. Keep the employer's marketing `remote` label separate from verified work-country eligibility. Mark missing or contradictory eligibility as unknown and surface it for review rather than inventing permission. Keep the owner's target salary in private configuration as a soft preference, not an automatic rejection threshold; lower-paid roles can remain visible when they offer a credible employment option. Do not commit an individual's salary target, residence, applications or employment decisions to this public repository.

Measure usefulness after filtering: suitable vacancies, applications sent, interview invitations, rejections with known reasons, and application-to-interview conversion. Found-vacancy count is a diagnostic, not the main success measure. Start real applications when the useful shortlist and human review are ready; later phases can improve the agent while applications continue.

Greenhouse, Lever and the bounded search pilot are the current discovery inputs. Verify that they yield enough suitable, actionable vacancies before adding providers. Expand sources only when observed yield or coverage is insufficient; record why each new source is needed. Search-pilot URLs remain unverified until an explicit verification step.

## 4. Code and repository architecture

Start with one `job-agent` repository containing a modular application and infrastructure as code. Extract a platform repository when a second agent actually uses shared components.

Domain modules/packages:

- `source`: discovery/Gmail adapters, raw payloads and provenance.
- `vacancy`: employers, vacancies, normalization and deduplication.
- `evaluation`: criteria, LLM gateway, prompt versions and evaluations.
- `review`: shortlist, user decisions and feedback.
- `career`: structured career profile and private-document references.
- `application`: drafts, explicit approval and submission history.
- `outcome`: responses, interviews, offers and results.
- `run`: scheduling, idempotency, errors and pending external work.
- `infra`: migrations, AWS configuration and deployment.

When user review is introduced, minimal APIs retrieve the shortlist, show a vacancy with evaluation/source URL, save feedback and show run history. Later APIs create/approve/reject application drafts and record outcomes. Scheduled batch execution does not require HTTP controllers. A protected report can precede a UI; agree delivery and access controls before personal data is connected.

Event processing follows [ADR 0001](docs/adr/0001-event-driven-processing.md). Phase 2B locally implements a durable outbox and synthetic-handler delivery/retry tests; Phases 3–4 connect real discovery/evaluation work. The user selected SQS as the cloud transport; queue design, workers and complete cost remain for AWS deployment. Do not treat the local increment as deployed cloud processing.

Layers and naming follow [ADR 0002](docs/adr/0002-layers-and-naming.md): Controller → Service → Repository/Mapper, `Entity`/`DTO` suffixes and separate `exception`, `exceptionhandler` and `config` packages. Start with domain packages and internal layers in one Maven project. Separate Maven modules require a concrete justification. Introduce automatic forbidden-dependency checks as layers appear.

## 5. Incremental data model

Phase 1 delivered `agent_runs`. Foundation migrations in subsequent tasks introduce `source_items`, `processed_messages`, `employers`, `vacancies`, `vacancy_sources` and `external_actions`, as required by scope. Use unique source identities and action idempotency keys; employer name alone is not an identity.

Then introduce `evaluation_criteria`, `vacancy_evaluations`, `review_feedback`, `searches` and `shortlists`. When needed, add `career_profiles`, `skills`, `experience`, `projects`, `certifications`, `education`, `languages`, `documents`, `cv_versions`, `applications` and `outcomes`. Embeddings/pgvector come later. Preserve raw payload and extraction versions for reprocessing.

## 6. Phases and acceptance criteria

| Phase | Scope | Done when |
| --- | --- | --- |
| 0. Requirements and architecture | Inspect old Sheet/workflow; define roles, geography, criteria and discovery sources; establish regional cost assumptions. | Requirements, inputs/outputs, source choices, suitable/unsuitable examples and preliminary AWS design/estimate are documented. Completed 2026-10-07; provider costs and deployment budget remain follow-up decisions. |
| 1. Foundation | Java/Spring Boot, Docker PostgreSQL, migrations, secret-free configuration and CI. | Clean documented startup and passing checks. Completed in PR #2 on 2026-10-11. |
| 2. Persistent state and safe processing | Runs, source inputs, vacancies, stable IDs, transactions and pending events/actions. | Repeated inputs create no duplicates; failed DB writes trigger no external action; failed processing can retry safely. |
| 3. Independent discovery | First agreed non-Gmail sources, source URLs/raw data, normalization and deduplication. | Real new vacancies are discovered without LinkedIn alerts and repeat runs do not duplicate them. |
| 4. Evaluation and shortlist | Versioned private selection criteria, initial filters, cloud LLM and explained shortlist with provenance and explicit eligibility unknowns. | A reviewable shortlist and evaluation cases demonstrate which vacancies pass and why, including role families, actual remote eligibility and salary preference. First useful MVP. |
| 5. AWS | Application, PostgreSQL, S3, secrets, schedule, logs, alarms, backups and restore test. | Runs work without a MacBook; costs/errors are visible; budget notification and recovery are verified. |
| 6. Feedback | User decisions/reasons, criteria/prompt comparisons and shortlist-quality metrics. | Feedback persists and can affect later results without losing history; suitable-vacancy yield is visible. |
| 7. Old pipeline and cutover | Gmail OAuth/alerts, legacy rules, full Sheet snapshot/final delta, reconciliation and master switch. | Successful cloud rehearsal, reconciliation and retry checks precede disabling the old writer. Sheet stays the old master until then. |
| 8. Semantic matching | Embeddings/pgvector after baseline quality evaluation. | Measured improvement over ordinary rules justifies semantic matching. |
| 9. Analytics and strategy | Market/employer/skill trends, search strategy and career intelligence. | Recommendations cite accumulated vacancies and user feedback. |
| 10. Applications and outcomes | Career profile, S3 CV versions, drafts, approval and application outcomes. | No submission without approval; vacancy → application → CV version → outcome is traceable, including interview conversion and known rejection reasons. |
| 11. Optional ML | Consider a separate model only with sufficient data and measurable benefit. | Comparison supports the decision; this phase may be omitted. |

## 7. Small PRs

The original sequence covered contract/source/cost design, application foundation, safe persistence, the first source, the second source/deduplication, LLM/shortlist and AWS monitoring/restore. Phase 1 has now landed in PR #2. Insert the separate JaCoCo/Sonar quality increment before Phase 2 business logic; PR numbering is assigned by GitHub, not fixed by this illustrative sequence.

Use separate subsequent PRs for feedback, Gmail OAuth, import/reconciliation, cutover and each product increment. After each merged PR, update existing Career Board status from facts and preserve verified CV/LinkedIn/interview material in `CAREER_EVIDENCE.md` and Narrative. No automatic publication.

## 8. Critical rules

- Keep one master for the old pipeline until cutover. Isolated test copies must not trigger real actions.
- Keep secrets/private documents out of Git. Request CVs and employment references only after private S3 storage is ready and verified.
- Persist state before external actions; application submissions require separate explicit approval.
- Do not present plans as delivered features. Public career claims require code/tests/results; exclude confidential commercial details.
- The low monthly AWS estimate is assumption-based, not a measured bill. Validate regional deployment rates, compute, storage, networking, logs and backups before deployment. Avoid unnecessary NAT Gateway standing costs.

## 9. Next development steps and chat responsibilities

Phase 0 is complete; repeat research only for a concrete reason. Phase 1 delivered the runnable foundation. Quality configuration is merged in PR #4. Phase 2A merged synthetic persistence, identity handling and rollback in PR #5. Phase 2B merged durable outbox delivery in PR #6. PR #7 merged pre-merge Sonar analysis and cleanup. Phase 3A merged in PR #8: it implements the first explicit Greenhouse board adapter with board-scoped identity and versioned updates. Phase 3B merged configured Lever site retrieval with observation ordering in PR #9. Phase 3C.1 merged a bounded, explicit Tavily Basic search pilot for unverified URL candidates in PR #10; see [ADR 0003](docs/adr/0003-tavily-search-pilot.md). A bounded live pilot made 4 requests and returned 34 unique URL candidates without errors; 96 of 100 persistent attempt slots remained at that observation. Those candidates are not verified sources or vacancies. Phase 3C.2 merged source verification and a controlled registry in PR #12; a 2026-10-27 isolated exercise verified one Mashgin association, explicit activation and idempotent Lever import. This does not establish broad employer discovery or fit evaluation. The broader first technical increment described in Phase 0 spans Phases 1–2 and need not be one PR.

Phase 4A introduces an explicit, versioned private profile, conservative deterministic rules and an explained local shortlist. It uses V9 evaluation history and merged in PR #14. Role title matches do not establish applicant qualification; live `remote` labels do not establish work-country eligibility. Phase 4B.1 prepares separately reviewable Anthropic extraction with V10 pilot accounting, without changing rules or making paid calls. Event-driven evaluation and trusted eligibility remain later work. See [Phase 4 task](docs/PHASE_4_TASK.md), [ADR 0005](docs/adr/0005-rule-evaluation-baseline.md) and [ADR 0006](docs/adr/0006-anthropic-extraction-pilot.md).

The original Work chat plans work, prepares implementation prompts, reviews results, may commit locally after successful review and prepares PR titles/descriptions. The implementation chat makes all source-code, configuration, documentation and translation changes, runs local checks and hands off uncommitted work with a concise file/check/limitation report. The user pushes, creates PRs, merges and deletes remote branches.

## 10. Repository setup and permanent instructions

The public repository exists. Its initial commit included root `AGENTS.md` and project plans. Maintain these setup requirements:

1. Keep AGENTS aligned with this plan and PHASE_0, including agreed technical/organizational rules, quality and resource policies.
2. Keep README links to permanent instructions and the master plan.
3. Check that published files contain no personal inputs/secrets.
4. Deliver subsequent changes through separate branches/PRs with concrete results and relevant checks.
5. Configure and verify Job Agent integration with the existing merged-PR Career Board/Narrative mechanism as a separate task; do not claim it works before verification.

Do not create a separate Work session solely for AGENTS. GitHub is the technical state source; the existing AI Career Board remains the shared career-program tracker. All repository content is English; user conversations may be Russian.

## 11. Quality checks and current boundary

PR #4 merged JaCoCo HTML/XML coverage, child-JVM instrumentation and main-push Sonar CI preparation. PR #7 merged a distinct Sonar job for trusted PRs targeting `main` and retained main-push analysis. PRs #7 and #8 passed real pre-merge `Build and test` and `Sonar Quality Gate` checks, but their separate merge-commit main-push gates failed at the time. For main commit `b90c3c49ca55df6e54660a102a9e49c8183306e1`, both jobs passed in [workflow run 40479903174, attempt 2](https://github.com/AlexS968/job-agent/actions/runs/40479903174). This does not establish closure of every open Sonar issue.

- JaCoCo: HTML/XML application coverage from unit/integration tests, including instrumented ProcessBuilder child JVMs.
- Sonar: CI-based SonarQube Cloud analysis imports JaCoCo XML after verify when repository configuration is present; the named main jobs passed on the revision and run linked above. Review later remote analyses and their actual gate results independently.
- Quality Gate: bugs, vulnerabilities, maintainability, duplication and new-code coverage; security hotspots require review. Agree actual service capabilities and thresholds during setup; no arbitrary percentage is already approved.
- CI: tests/JaCoCo work independently of Sonar access. The trusted-PR Sonar job waits for the gate; fork and bot PRs do not receive a token and skip it. PRs #7, #8 and #9 passed pre-merge analysis; the named jobs also passed on main commit `b90c3c4` in run 40479903174, attempt 2. Required GitHub-check settings remain unverified, and PR #10's separate post-merge gate status was not independently checked here.
- Secrets: user configures Sonar credentials through GitHub Secrets; never publish tokens or expose them to untrusted PRs. AI does not push or create PRs.
- Acceptance: clean verify produces a nonempty report covering JobAgentApplication; Sonar displays imported coverage and actual gate results. Record commands, evidence, limitations and justified exclusions.

References: [JaCoCo agent](https://www.jacoco.org/jacoco/trunk/doc/agent.html), [Sonar Java coverage](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/test-coverage/java-test-coverage/).

## 12. Mapping and boilerplate

The user selected MapStruct as the mapping standard. Introduce it with the first actual DTO/domain mapping in Phase 2; use shared Spring/constructor-injection configuration and compile errors for unmapped target properties. Keep field conversion separate from evaluation/normalization business rules.

Limited Lombok use is recommended: DI constructors, logging and selected getters/builders. Adoption and the precise annotation set remain to be agreed. Immutable DTOs/events may use Java records; do not apply @Data automatically to entities. Keep toString safe. MapStruct is already configured and verified in clean builds; Lombok is not configured.

References: [MapStruct](https://mapstruct.org/documentation/stable/reference/html/), [Lombok constructors](https://projectlombok.org/features/constructor), [Lombok Data](https://projectlombok.org/features/Data).
