# Next task: JaCoCo and Sonar

Status: task after completed Phase 1; tools are not yet configured.

## Deliverable

Verified application coverage and prepared Sonar analysis with a Quality Gate. Deliver as a separate increment before Phase 2 business logic.

## Implementation

1. Read AGENTS.md, MASTER_PLAN.md, PHASE_0.md and this task. Preserve existing work. Use local codex/quality-gates from the state prepared by the original chat. Check the base; do not switch to an outdated main and lose new instructions.
2. Add a stable Java-21-compatible jacoco-maven-plugin. Clean verify generates HTML/XML, including JobAgentApplication execution in integration-test child JVMs. Failsafe prepare-agent does not automatically reach ProcessBuilder: explicitly instrument child JVMs, combine multiple runs and include their data in the report. Do not reuse stale exec files after clean. Preserve batch exit codes and safe diagnostics.
3. Verify covered application lines/branches exist in the report. Do not exclude JobAgentApplication or write meaningless tests to inflate the percentage. Agree justified thresholds before introducing a blocking coverage check; report measurements and a proposal first.
4. Prepare Maven/SonarScanner and CI analysis after verify, importing JaCoCo XML. SonarQube Cloud is a candidate; do not invent organization/project keys or create paid resources. Verify current service terms and PR-analysis capabilities in official documentation. Without account/keys, prepare documented GitHub Variables/Secrets configuration and exact user setup steps.
5. SONAR_TOKEN belongs only in GitHub Secrets; do not read or publish it. Never expose secrets to untrusted PRs or run unverified code through pull_request_target. Ordinary build/test/coverage is independent of Sonar access. Missing Sonar configuration is explicitly not configured, not a successful analysis.
6. Prepare a Quality Gate for bugs, security, maintainability, duplication and new-code coverage; document actual capabilities/thresholds. Do not claim a gate or required GitHub check is configured without verification. Account/repository settings are configured by the user with the original chat.
7. Update README with verified commands, report paths, analysis conditions and variable/secret setup. Follow naming/layer rules. Do not add business logic, AWS, a broker, MapStruct or Lombok outside this task's actual scope.

## Handoff

Run ./mvnw -B clean verify and git diff --check; inspect JaCoCo XML/HTML. Report test count, coverage and included classes. Mark Sonar analysis and remote CI as not executed when access/push is unavailable.

Do not commit, push, create PRs or merge. The original chat reviews and may commit; the user pushes and creates the PR using the prepared title and description.

Keep the report concise: result, created/changed files, checks, measured coverage, limitations and user setup steps for Sonar. Verify the real Sonar Quality Gate after account setup and publication; do not claim the whole increment is complete beforehand.
