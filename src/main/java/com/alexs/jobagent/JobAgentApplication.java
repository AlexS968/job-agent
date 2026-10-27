package com.alexs.jobagent;

import com.alexs.jobagent.source.dto.IngestionResultDTO;
import com.alexs.jobagent.source.dto.SyntheticInputDTO;
import com.alexs.jobagent.source.service.SyntheticIngestionService;
import com.alexs.jobagent.source.service.GreenhouseBatchService;
import com.alexs.jobagent.source.dto.GreenhouseBatchResultDTO;
import com.alexs.jobagent.source.dto.LeverBatchResultDTO;
import com.alexs.jobagent.source.service.LeverBatchService;
import com.alexs.jobagent.source.service.SearchPilotService;
import com.alexs.jobagent.source.service.SourceVerificationService;
import com.alexs.jobagent.source.service.SourceRegistryService;
import com.alexs.jobagent.source.dto.SourceCandidateDTO;
import com.alexs.jobagent.source.dto.SearchPilotResultDTO;
import com.alexs.jobagent.outbox.dto.DispatchResultDTO;
import com.alexs.jobagent.outbox.service.LocalEventDispatcher;
import com.alexs.jobagent.outbox.service.OutboxService;
import com.alexs.jobagent.evaluation.service.EvaluationService;
import com.alexs.jobagent.evaluation.service.ProfileFileService;
import com.alexs.jobagent.evaluation.service.RuleEvaluationPolicy;
import com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

@SpringBootApplication
public class JobAgentApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobAgentApplication.class);

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = SpringApplication.run(JobAgentApplication.class, args)) {
            // Closing the context releases the connection pool before the batch process exits.
        } catch (Exception exception) {
            LOGGER.error("Job Agent startup failed; check database availability and configuration.");
            System.exit(1);
        }
    }

    @Bean
    CommandLineRunner verifyDatabase(Flyway flyway, JdbcTemplate jdbc,
                                     SyntheticIngestionService ingestion, JsonMapper json,
                                     LocalEventDispatcher dispatcher, OutboxService outbox,
                                     GreenhouseBatchService greenhouse,
                                     @Value("${GREENHOUSE_BOARDS:}") String boards,
                                     LeverBatchService lever,
                                     @Value("${LEVER_SITES:}") String leverSites,
                                     SearchPilotService searchPilot,
                                     SourceVerificationService verifier, SourceRegistryService registry,
                                     ProfileFileService profiles, EvaluationService evaluations) {
        return args -> {
            flyway.validate();
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs", Integer.class);
            LOGGER.info("Database ready; agent_runs rows: {}", count);
            executeMode(args, ingestion, json, dispatcher, outbox, greenhouse, boards, lever, leverSites,
                    searchPilot, verifier, registry, profiles, evaluations);
        };
    }

    private void executeMode(String[] args, SyntheticIngestionService ingestion, JsonMapper json,
                             LocalEventDispatcher dispatcher, OutboxService outbox,
                             GreenhouseBatchService greenhouse, String boards,
                             LeverBatchService lever, String leverSites, SearchPilotService searchPilot,
                             SourceVerificationService verifier, SourceRegistryService registry,
                             ProfileFileService profiles, EvaluationService evaluations) throws IOException {
        String[] modes = Arrays.stream(args).filter(arg -> arg.startsWith("--mode=")).toArray(String[]::new);
        if (modes.length == 0) {
            return;
        }
        if (modes.length != 1) {
            throw new IllegalArgumentException("Unsupported batch mode");
        }
        switch (modes[0]) {
            case "--mode=synthetic" -> runSynthetic(args, ingestion, json);
            case "--mode=dispatch" -> runDispatch(args, dispatcher);
            case "--mode=replay-failed" -> runReplay(args, outbox);
            case "--mode=greenhouse" -> runGreenhouse(args, greenhouse, boards);
            case "--mode=lever" -> runLever(args, lever, leverSites);
            case "--mode=search-dry-run" -> runSearchDryRun(args, searchPilot);
            case "--mode=search-pilot" -> runSearchPilot(args, searchPilot);
            case "--mode=verify-source" -> runVerifySource(args, verifier);
            case "--mode=review-sources" -> runReviewSources(args, registry);
            case "--mode=activate-source" -> runActivateSource(args, registry);
            case "--mode=import-active-sources" -> runImportActiveSources(args, registry);
            case "--mode=evaluate-rules" -> runEvaluateRules(args, profiles, evaluations);
            case "--mode=shortlist" -> runShortlist(args, profiles, evaluations);
            default -> throw new IllegalArgumentException("Unsupported batch mode");
        }
    }

    private void runVerifySource(String[] args, SourceVerificationService verifier) {
        List<String> urls = Arrays.stream(args).filter(arg -> arg.startsWith("--candidate-url="))
                .map(arg -> arg.substring("--candidate-url=".length())).toList();
        if (urls.isEmpty() || urls.size() > SourceVerificationService.MAX_CANDIDATES
                || args.length != urls.size() + 5) {
            throw new IllegalArgumentException("Verification requires one to five candidates and four metadata arguments");
        }
        String origin = option(args, "--origin=");
        String domain = option(args, "--official-domain=");
        String page = option(args, "--official-page-url=");
        String name = option(args, "--employer-name=");
        var candidates = urls.stream().map(url -> new SourceCandidateDTO(url, origin, domain, page, name)).toList();
        var results = verifier.verify(candidates);
        results.forEach(result -> LOGGER.info("Source verification: outcome={}, reason={}, identity={}, requests={}, evidence={}",
                result.outcome(), result.reason(), result.identity(), result.requests(), result.evidenceHash()));
        if (results.stream().anyMatch(result -> !"VERIFIED".equals(result.outcome()))) {
            throw new IllegalStateException("Source verification requires review or retry");
        }
    }

    private void runReviewSources(String[] args, SourceRegistryService registry) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Unsupported review arguments");
        }
        registry.sources().forEach(source -> LOGGER.info(
                "Registry source: id={}, platform={}, region={}, source_id={}, employer_domain={}, status={}, active={}, verified_at={}",
                source.id(), source.platform(), source.region(), source.sourceId(), source.employerDomain(),
                source.status(), source.active(), source.verifiedAt()));
        registry.attempts().forEach(attempt -> LOGGER.info(
                "Verification evidence: observed_at={}, origin={}, outcome={}, reason={}, identity={}, requests={}, evidence={}",
                attempt.observedAt(), attempt.origin(), attempt.outcome(), attempt.reason(), attempt.identity(),
                attempt.requests(), attempt.evidenceHash()));
    }

    private void runActivateSource(String[] args, SourceRegistryService registry) {
        if (args.length != 2 || !args[1].startsWith("--source-id=")) {
            throw new IllegalArgumentException("A source ID is required");
        }
        long id = Long.parseLong(args[1].substring("--source-id=".length()));
        if (!registry.activate(id)) {
            throw new IllegalStateException("Source is not eligible for activation");
        }
        LOGGER.info("Source activated: id={}", id);
    }

    private void runImportActiveSources(String[] args, SourceRegistryService registry) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Unsupported import arguments");
        }
        var result = registry.importActive();
        LOGGER.info("Active source import: sources={}, failures={}", result.sources(), result.failures());
        if (result.failures() > 0) {
            throw new IllegalStateException("Active source import had failures");
        }
    }

    private void runEvaluateRules(String[] args, ProfileFileService profiles, EvaluationService evaluations) {
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException("Evaluation requires a profile file and optional cursor/limit");
        }
        requireOnlyOptions(args, "--mode=evaluate-rules", "--profile-file=", "--after-id=", "--limit=");
        var profile = profiles.load(option(args, "--profile-file="));
        long after = optionalLong(args, "--after-id=", 0);
        int limit = Math.toIntExact(optionalLong(args, "--limit=", 100));
        var result = evaluations.evaluate(profile, after, limit, RuleEvaluationPolicy.VERSION);
        LOGGER.info("Rule evaluation: examined={}, created={}, already_current={}, last_vacancy_id={}, more_available={}",
                result.examined(), result.created(), result.alreadyCurrent(), result.lastVacancyId(),
                result.moreAvailable());
    }

    private void runShortlist(String[] args, ProfileFileService profiles, EvaluationService evaluations) {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Shortlist requires a profile file and optional limit");
        }
        requireOnlyOptions(args, "--mode=shortlist", "--profile-file=", "--limit=");
        var profile = profiles.load(option(args, "--profile-file="));
        int limit = Math.toIntExact(optionalLong(args, "--limit=", 20));
        var report = evaluations.shortlist(profile, limit, RuleEvaluationPolicy.VERSION);
        LOGGER.info("Shortlist: suitable={}, needs_review={}", report.suitable().size(), report.needsReview().size());
        report.suitable().forEach(item -> logEvaluation("SUITABLE", item));
        report.needsReview().forEach(item -> logEvaluation("NEEDS_REVIEW", item));
    }

    private void logEvaluation(String group, VacancyEvaluationDTO item) {
        LOGGER.info("{} vacancy: id={}, rank={}, url={}, reasons={}, unknowns={}, evidence={}",
                group, item.vacancyId(), item.preferenceRank(), safeSourceUrl(item.originalUrl()),
                item.reasons(), item.unknowns(), item.evidence());
    }

    private static String safeSourceUrl(String url) {
        String oneLine = url.replaceAll("[\\p{Cntrl}]", " ");
        return oneLine.substring(0, Math.min(oneLine.length(), 500));
    }

    private long optionalLong(String[] args, String prefix, long fallback) {
        List<String> values = Arrays.stream(args).filter(arg -> arg.startsWith(prefix)).toList();
        if (values.isEmpty()) {
            return fallback;
        }
        if (values.size() != 1) {
            throw new IllegalArgumentException("Invalid batch arguments");
        }
        return Long.parseLong(values.getFirst().substring(prefix.length()));
    }

    private void requireOnlyOptions(String[] args, String mode, String... prefixes) {
        for (String argument : args) {
            if (mode.equals(argument)) {
                continue;
            }
            if (Arrays.stream(prefixes).noneMatch(argument::startsWith)) {
                throw new IllegalArgumentException("Unsupported evaluation arguments");
            }
        }
    }

    private String option(String[] args, String prefix) {
        List<String> values = Arrays.stream(args).filter(arg -> arg.startsWith(prefix)).toList();
        if (values.size() != 1) {
            throw new IllegalArgumentException("Invalid verification arguments");
        }
        return values.getFirst().substring(prefix.length());
    }

    private void runSearchDryRun(String[] args, SearchPilotService pilot) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Unsupported search arguments");
        }
        LOGGER.info("Search query set: version={}, count={}", SearchPilotService.QUERY_VERSION,
                pilot.plannedQueries().size());
        pilot.plannedQueries().forEach(query -> LOGGER.info("Planned search: {}", query.query()));
    }

    private void runSearchPilot(String[] args, SearchPilotService pilot) {
        if (args.length != 1 || System.getenv("TAVILY_API_KEY") == null
                || System.getenv("TAVILY_API_KEY").isBlank()) {
            throw new IllegalArgumentException("Search pilot requires local TAVILY_API_KEY and no extra arguments");
        }
        SearchPilotResultDTO result;
        try {
            result = pilot.run();
        } catch (IllegalStateException exhausted) {
            if (exhausted.getMessage() != null && exhausted.getMessage().startsWith("Search pilot budget exhausted")) {
                LOGGER.error("Search pilot budget exhausted; no further request sent");
            }
            throw exhausted;
        }
        LOGGER.info("Search pilot: queries={}, outbound_attempts={}, remaining_budget={}, unverified_candidates={}, failures={}, cancelled={}",
                result.queries(), result.attempts(), result.remaining(), result.unverifiedUrls().size(),
                result.failures(), result.cancelled());
        result.unverifiedUrls().forEach(url -> LOGGER.info("UNVERIFIED candidate URL: {}", url));
        if (result.failures() > 0) {
            throw new IllegalStateException("Search pilot had failed queries");
        }
    }

    private void runLever(String[] args, LeverBatchService lever, String sites) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Unsupported lever arguments");
        }
        LeverBatchResultDTO result = lever.run(sites);
        if (result.failed() > 0 || result.rejected() > 0) {
            throw new IllegalStateException("Lever invocation had failed or rejected work");
        }
    }

    private void runGreenhouse(String[] args, GreenhouseBatchService greenhouse, String boards) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Unsupported greenhouse arguments");
        }
        GreenhouseBatchResultDTO result = greenhouse.run(boards);
        if (result.failed() > 0 || result.rejected() > 0) {
            throw new IllegalStateException("Greenhouse invocation had failed or rejected work");
        }
    }

    private void runDispatch(String[] args, LocalEventDispatcher dispatcher) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Unsupported dispatch arguments");
        }
        DispatchResultDTO result = dispatcher.dispatch(20);
        LOGGER.info("Dispatch invocation: completed={}, deferred={}, failed={}",
                result.completed(), result.deferred(), result.failed());
        LOGGER.info("Backlog snapshot: ready={}, future_retries={}, active_claims={}, expired_claims={}, terminal_failures={}",
                result.backlog().ready(), result.backlog().futureRetries(), result.backlog().activeClaims(),
                result.backlog().expiredClaims(), result.backlog().terminalFailures());
        if (result.failed() > 0) {
            throw new IllegalStateException("Dispatch has failed events");
        }
    }

    private void runReplay(String[] args, OutboxService outbox) {
        if (args.length != 2 || !args[1].startsWith("--event-id=")) {
            throw new IllegalArgumentException("A failed event ID is required");
        }
        UUID eventId = UUID.fromString(args[1].substring("--event-id=".length()));
        if (!outbox.replayFailed(eventId)) {
            throw new IllegalStateException("Event is not in FAILED state");
        }
        LOGGER.info("Failed event queued for replay: event_id={}", eventId);
    }

    private void runSynthetic(String[] args, SyntheticIngestionService ingestion, JsonMapper json) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Unsupported batch mode");
        }
        try (InputStream fixture = JobAgentApplication.class.getResourceAsStream("/synthetic/source-item.json")) {
            if (fixture == null) {
                throw new IllegalStateException("Synthetic fixture is unavailable");
            }
            SyntheticInputDTO input = json.readValue(fixture, SyntheticInputDTO.class);
            IngestionResultDTO result = ingestion.ingest(input);
            LOGGER.info("Synthetic ingestion complete: run_id={}, source_created={}, vacancy_created={}",
                    result.runId(), result.sourceCreated(), result.vacancyCreated());
        }
    }
}
