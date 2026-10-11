package com.alexs.jobagent;

import com.alexs.jobagent.source.dto.IngestionResultDTO;
import com.alexs.jobagent.source.dto.SyntheticInputDTO;
import com.alexs.jobagent.source.service.SyntheticIngestionService;
import com.alexs.jobagent.outbox.dto.DispatchResultDTO;
import com.alexs.jobagent.outbox.service.LocalEventDispatcher;
import com.alexs.jobagent.outbox.service.OutboxService;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootApplication
public class JobAgentApplication {
    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = SpringApplication.run(JobAgentApplication.class, args)) {
            // Closing the context releases the connection pool before the batch process exits.
        } catch (Exception exception) {
            System.err.println("Job Agent startup failed; check database availability and configuration.");
            System.exit(1);
        }
    }

    @Bean
    CommandLineRunner verifyDatabase(Flyway flyway, JdbcTemplate jdbc,
                                     SyntheticIngestionService ingestion, JsonMapper json,
                                     LocalEventDispatcher dispatcher, OutboxService outbox) {
        return args -> {
            flyway.validate();
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs", Integer.class);
            System.out.println("Database ready; agent_runs rows: " + count);
            String[] modes = Arrays.stream(args).filter(arg -> arg.startsWith("--mode=")).toArray(String[]::new);
            if (modes.length == 0) {
                return;
            }
            if (modes.length != 1) {
                throw new IllegalArgumentException("Unsupported batch mode");
            }
            if ("--mode=dispatch".equals(modes[0])) {
                if (args.length != 1) {
                    throw new IllegalArgumentException("Unsupported dispatch arguments");
                }
                DispatchResultDTO result = dispatcher.dispatch(20);
                System.out.println("Dispatch invocation: completed=" + result.completed()
                        + ", deferred=" + result.deferred() + ", failed=" + result.failed());
                System.out.println("Backlog snapshot: ready=" + result.backlog().ready()
                        + ", future_retries=" + result.backlog().futureRetries()
                        + ", active_claims=" + result.backlog().activeClaims()
                        + ", expired_claims=" + result.backlog().expiredClaims()
                        + ", terminal_failures=" + result.backlog().terminalFailures());
                if (result.failed() > 0) {
                    throw new IllegalStateException("Dispatch has failed events");
                }
                return;
            }
            if ("--mode=replay-failed".equals(modes[0])) {
                if (args.length != 2 || !args[1].startsWith("--event-id=")) {
                    throw new IllegalArgumentException("A failed event ID is required");
                }
                UUID eventId = UUID.fromString(args[1].substring("--event-id=".length()));
                if (!outbox.replayFailed(eventId)) {
                    throw new IllegalStateException("Event is not in FAILED state");
                }
                System.out.println("Failed event queued for replay: event_id=" + eventId);
                return;
            }
            if (!"--mode=synthetic".equals(modes[0]) || args.length != 1) {
                throw new IllegalArgumentException("Unsupported batch mode");
            }
            try (InputStream fixture = JobAgentApplication.class.getResourceAsStream("/synthetic/source-item.json")) {
                if (fixture == null) {
                    throw new IllegalStateException("Synthetic fixture is unavailable");
                }
                SyntheticInputDTO input = json.readValue(fixture, SyntheticInputDTO.class);
                IngestionResultDTO result = ingestion.ingest(input);
                System.out.println("Synthetic ingestion complete: run_id=" + result.runId()
                        + ", source_created=" + result.sourceCreated()
                        + ", vacancy_created=" + result.vacancyCreated());
            }
        };
    }
}
