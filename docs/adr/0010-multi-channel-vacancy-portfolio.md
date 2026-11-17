# ADR 0010: Direct vacancy channels and employer career sources

Status: accepted target direction, 2026-11-17. Implementation and AWS rollout remain pending.

## Context

The deployed Greenhouse/Lever import works for verified boards but covers only one active employer in the observed production sample: 22 stored Solaris listings yielded one role-relevant Germany vacancy and no confirmed Austria recommendation. Those 22 listings were not 22 suitable Java/backend openings. A controlled Tavily search returned 35 URL leads; the five leads checked in that run activated no new source or imported vacancy. A later controlled verification run also activated none. This is evidence that the **current deployed discovery path has not produced sufficient useful coverage**, not proof that Greenhouse, Lever or every possible employer board is inherently unproductive.

The owner's existing LinkedIn alert pipeline already receives many vacancies, while a bounded Adzuna sample demonstrated DE/AT results. These are reasons to evaluate direct vacancy channels, not proof of their long-term coverage, price or access rights. Requiring an official employer page before accepting an aggregator vacancy would discard useful vacancies when that page is empty or unavailable. The decision therefore changes the intake architecture while retaining the working board adapters as optional employer-career readers.

## Decision

Use two independent intake paths: direct vacancy observations from approved alert/API/feed channels and vacancies from verified employer career sources. Start with LinkedIn alerts through separately authorized Java Gmail access, then reconcile and cut over the old Sheet pipeline. Build canonical employer identities from evidence, preserving provider aliases and every vacancy observation. An official page is an optional monitoring capability, not a prerequisite for direct-channel vacancies. Measure usefulness separately for Germany, Austria, Remote Europe and Remote Worldwide; remote labels do not establish work-country eligibility. Adopt additional channels only after measured yield, terms, operational and cost review. Keep Greenhouse/Lever as useful career-page adapters. Retire obsolete Tavily/ATS-only orchestration only after replacement and data reconciliation. The [master plan](../../MASTER_PLAN.md), [two target diagrams](../TARGET_ARCHITECTURE.md) and [retirement register](../SOURCE_ARCHITECTURE_RETIREMENT_REGISTER.md) govern delivery.

## Consequences

Phase 7 precedes renewed Phase 9A.1 source expansion. Existing Phase 3 and 9A experiments remain historical evidence rather than acceptance of broad coverage. Cross-channel identity, source budgets, retries, provenance and truthful partial failures become explicit contracts. Monthly market analysis must account for changes in source coverage; AI recommendations and application drafts require separate evidence, cost controls and owner review. No new paid service, source crawl or AWS change is authorized by this decision alone.
