# Job Agent: Complete delivery plan

Status: 2026-10-11. Phase 1 is complete: PR #2 merged on 2026-10-11. JaCoCo and Sonar CI preparation merged in PR #4; remote Sonar analysis and the actual Quality Gate remain unverified. Phase 2A synthetic persistence is in local implementation; Phase 2B outbox/dispatcher follows. This is the authoritative development entry point. `PROJECT_PLAN.md` and `FIRST_INCREMENT.md` contain supporting detail; this plan takes precedence in a conflict.

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

Event processing follows [ADR 0001](docs/adr/0001-event-driven-processing.md). Phase 2 introduces a durable outbox and synthetic-handler delivery/retry tests; Phases 3–4 connect real vacancy/evaluation events. The user selected SQS as the cloud transport; design queues, workers and complete cost before AWS deployment. This remains planned event-driven implementation.

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
| 4. Evaluation and shortlist | Explicit criteria, initial filters, cloud LLM and explained shortlist with provenance. | A reviewable shortlist and evaluation cases demonstrate which vacancies pass and why. First useful MVP. |
| 5. AWS | Application, PostgreSQL, S3, secrets, schedule, logs, alarms, backups and restore test. | Runs work without a MacBook; costs/errors are visible; budget notification and recovery are verified. |
| 6. Feedback | User decisions/reasons, criteria/prompt comparisons and shortlist-quality metrics. | Feedback persists and can affect later results without losing history. |
| 7. Old pipeline and cutover | Gmail OAuth/alerts, legacy rules, full Sheet snapshot/final delta, reconciliation and master switch. | Successful cloud rehearsal, reconciliation and retry checks precede disabling the old writer. Sheet stays the old master until then. |
| 8. Semantic matching | Embeddings/pgvector after baseline quality evaluation. | Measured improvement over ordinary rules justifies semantic matching. |
| 9. Analytics and strategy | Market/employer/skill trends, search strategy and career intelligence. | Recommendations cite accumulated vacancies and user feedback. |
| 10. Applications and outcomes | Career profile, S3 CV versions, drafts, approval and application outcomes. | No submission without approval; vacancy → application → CV version → outcome is traceable. |
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

Phase 0 is complete; repeat research only for a concrete reason. Phase 1 delivered the runnable foundation. Quality configuration is merged in PR #4. Phase 2A introduces synthetic persistence, identity handling and rollback; Phase 2B adds durable outbox delivery. The broader first technical increment described in Phase 0 spans Phases 1–2 and need not be one PR.

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

PR #4 merged JaCoCo HTML/XML coverage, child-JVM instrumentation and main-push Sonar CI preparation. Local coverage was verified. The public PR showed successful CI and SonarCloud checks, but the imported coverage and actual Quality Gate have not been independently verified. The main-only scanner runs after merge and does not provide a pre-merge Sonar gate.

- JaCoCo: HTML/XML application coverage from unit/integration tests, including instrumented ProcessBuilder child JVMs.
- Sonar: prepared SonarQube Cloud scanner imports JaCoCo XML after verify when repository configuration is present; verify actual remote analysis and gate independently.
- Quality Gate: bugs, vulnerabilities, maintainability, duplication and new-code coverage; security hotspots require review. Agree actual service capabilities and thresholds during setup; no arbitrary percentage is already approved.
- CI: tests/JaCoCo work independently of Sonar access. Main-push analysis is after merge; a pre-merge Sonar gate and required GitHub-check settings remain unverified.
- Secrets: user configures Sonar credentials through GitHub Secrets; never publish tokens or expose them to untrusted PRs. AI does not push or create PRs.
- Acceptance: clean verify produces a nonempty report covering JobAgentApplication; Sonar displays imported coverage and actual gate results. Record commands, evidence, limitations and justified exclusions.

References: [JaCoCo agent](https://www.jacoco.org/jacoco/trunk/doc/agent.html), [Sonar Java coverage](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/test-coverage/java-test-coverage/).

## 12. Mapping and boilerplate

The user selected MapStruct as the mapping standard. Introduce it with the first actual DTO/domain mapping in Phase 2; use shared Spring/constructor-injection configuration and compile errors for unmapped target properties. Keep field conversion separate from evaluation/normalization business rules.

Limited Lombok use is recommended: DI constructors, logging and selected getters/builders. Adoption and the precise annotation set remain to be agreed. Immutable DTOs/events may use Java records; do not apply @Data automatically to entities. Keep toString safe and verify MapStruct/Lombok processors in clean builds. Neither dependency is configured yet.

References: [MapStruct](https://mapstruct.org/documentation/stable/reference/html/), [Lombok constructors](https://projectlombok.org/features/constructor), [Lombok Data](https://projectlombok.org/features/Data).
