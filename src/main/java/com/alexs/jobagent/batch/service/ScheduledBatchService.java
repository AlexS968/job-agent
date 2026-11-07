package com.alexs.jobagent.batch.service;

import com.alexs.jobagent.batch.repository.ScheduledBatchRepository;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@ConditionalOnProperty(name = "jobagent.report.enabled", havingValue = "true")
public class ScheduledBatchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledBatchService.class);
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String PUBLISHING = "PUBLISHING";
    private final ScheduledBatchRepository repository;
    private final BatchPipelineService pipeline;
    private final BatchReportFormatter formatter;
    private final ReportGateway gateway;
    private final Clock clock;

    public ScheduledBatchService(ScheduledBatchRepository repository, BatchPipelineService pipeline,
                                 BatchReportFormatter formatter, ReportGateway gateway, Clock clock) {
        this.repository = repository;
        this.pipeline = pipeline;
        this.formatter = formatter;
        this.gateway = gateway;
        this.clock = clock;
    }

    /** One calendar slot in Europe/Berlin. Existing slots and ambiguous publications are never replayed. */
    public void run(LoadedProfileDTO profile) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Scheduled batch requires no ambient transaction");
        }
        LocalDate date = LocalDate.now(clock.withZone(BERLIN));
        if (!repository.start(date, profile.profile().profileId(), profile.profile().version())) {
            if (repository.exists(date)) {
                LOGGER.info("Scheduled batch slot {} already exists; no processing or report replay", date);
                return;
            }
            throw new IllegalStateException("Another batch invocation is active; manual review is required");
        }
        try {
            var result = pipeline.run(profile, 50, Duration.ofMinutes(15), 20);
            if ("FAILED".equals(result.outcome()) || "TIME_LIMIT".equals(result.outcome())) {
                throw new IllegalStateException("Batch pipeline failed or reached its time limit");
            }
            String body = formatter.format(date, profile, result);
            repository.transition(date, "RUNNING", "PREPARED", body, null);
            repository.transition(date, "PREPARED", PUBLISHING, null, null);
            publishPrepared(date, result.outcome(), body);
            LOGGER.info("Scheduled report: date={}, status=PUBLISHED, outcome={}", date, result.outcome());
        } catch (RuntimeException failure) {
            // A published or uncertain report must not be rewritten as a failed processing run.
            markIfPossible(date, "RUNNING", "FAILED");
            throw failure;
        }
    }

    private void publishPrepared(LocalDate date, String outcome, String body) {
        String messageId;
        try {
            messageId = gateway.publish("Job Agent batch " + date + ": " + outcome, body);
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalStateException("SNS did not return a message identity");
            }
        } catch (RuntimeException uncertain) {
            markIfPossible(date, PUBLISHING, "UNCERTAIN");
            throw new IllegalStateException("Report publication outcome is uncertain; review before any replay", uncertain);
        }
        repository.transition(date, PUBLISHING, "PUBLISHED", null, messageId);
    }

    private void markIfPossible(LocalDate date, String from, String to) {
        try {
            repository.transition(date, from, to, null, null);
        } catch (RuntimeException trackingFailure) {
            // Preserve the original error. A DB outage can also prevent status recording.
            LOGGER.warn("Scheduled batch status could not be recorded; inspect the database before retrying");
        }
    }
}
