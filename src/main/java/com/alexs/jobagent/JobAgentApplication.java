package com.alexs.jobagent;

import com.alexs.jobagent.source.dto.IngestionResultDTO;
import com.alexs.jobagent.source.dto.SyntheticInputDTO;
import com.alexs.jobagent.source.service.SyntheticIngestionService;
import com.alexs.jobagent.source.service.GreenhouseBatchService;
import com.alexs.jobagent.source.dto.GreenhouseBatchResultDTO;
import com.alexs.jobagent.source.dto.LeverBatchResultDTO;
import com.alexs.jobagent.source.service.LeverBatchService;
import com.alexs.jobagent.source.service.SearchPilotService;
import com.alexs.jobagent.source.dto.SearchPilotResultDTO;
import com.alexs.jobagent.outbox.dto.DispatchResultDTO;
import com.alexs.jobagent.outbox.service.LocalEventDispatcher;
import com.alexs.jobagent.outbox.service.OutboxService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
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
                                     SearchPilotService searchPilot) {
        return args -> {
            flyway.validate();
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs", Integer.class);
            LOGGER.info("Database ready; agent_runs rows: {}", count);
            executeMode(args, ingestion, json, dispatcher, outbox, greenhouse, boards, lever, leverSites, searchPilot);
        };
    }

    private void executeMode(String[] args, SyntheticIngestionService ingestion, JsonMapper json,
                             LocalEventDispatcher dispatcher, OutboxService outbox,
                             GreenhouseBatchService greenhouse, String boards,
                             LeverBatchService lever, String leverSites, SearchPilotService searchPilot) throws IOException {
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
            default -> throw new IllegalArgumentException("Unsupported batch mode");
        }
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
