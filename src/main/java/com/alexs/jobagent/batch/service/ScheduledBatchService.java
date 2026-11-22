package com.alexs.jobagent.batch.service;

import com.alexs.jobagent.source.exception.GmailAccessException;
import com.alexs.jobagent.source.service.GmailCutoverService;
import com.alexs.jobagent.batch.repository.ScheduledBatchRepository;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.service.EvaluationService;
import com.alexs.jobagent.evaluation.service.RuleEvaluationPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(name = "jobagent.scheduled.enabled", havingValue = "true")
public class ScheduledBatchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledBatchService.class);
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private final ScheduledBatchRepository repository;
    private final BatchPipelineService pipeline;
    private final EvaluationService evaluations;
    private final GmailCutoverService gmail;
    private final Clock clock;

    public ScheduledBatchService(ScheduledBatchRepository repository, BatchPipelineService pipeline,
                                 EvaluationService evaluations, GmailCutoverService gmail, Clock clock) {
        this.repository = repository;
        this.pipeline = pipeline;
        this.evaluations = evaluations;
        this.gmail = gmail;
        this.clock = clock;
    }

    /** One Berlin-date slot for collection and both profile sweeps. */
    public void run(List<LoadedProfileDTO> profiles) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Scheduled batch requires no ambient transaction");
        }
        requireProfiles(profiles);
        LocalDate date = LocalDate.now(clock.withZone(BERLIN));
        if ("COMPLETE".equals(repository.invocationStatus(date))) {
            LOGGER.info("Scheduled batch slot {} already completed; no processing repeated", date);
            return;
        }
        if (!repository.startInvocation(date)) {
            throw new IllegalStateException("Scheduled batch requires manual review of active work");
        }
        Instant started = clock.instant();
        Instant deadline = started.plus(Duration.ofMinutes(24));
        try {
            collectGmail(started.plus(Duration.ofMinutes(3)));
            var base = pipeline.runWithoutShortlist(profiles.getFirst(), 50, Duration.ofMinutes(10), 20);
            if ("FAILED".equals(base.outcome()) || "TIME_LIMIT".equals(base.outcome())) {
                throw new IllegalStateException("Batch pipeline failed or reached its time limit");
            }
            evaluateAll(profiles.getFirst(), deadline);
            evaluateAll(profiles.get(1), deadline);
            repository.finishInvocation(date, "COMPLETE");
            LOGGER.info("Scheduled collection: date={}, status=COMPLETE, de_evaluated=true, at_evaluated=true", date);
        } catch (RuntimeException failure) {
            markInvocationFailed(date);
            throw failure;
        }
    }

    private void evaluateAll(LoadedProfileDTO profile, Instant deadline) {
        long after = 0;
        boolean more;
        do {
            if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline)) {
                throw new IllegalStateException("Scheduled evaluation interrupted or reached its time limit");
            }
            var page = evaluations.evaluate(profile, after, 500, RuleEvaluationPolicy.VERSION);
            after = page.lastVacancyId();
            more = page.moreAvailable();
        } while (more);
    }

    private static void requireProfiles(List<LoadedProfileDTO> profiles) {
        if (profiles == null || profiles.size() != 2 || profiles.stream().anyMatch(p -> p == null || p.profile() == null
                || p.criteriaHash() == null || !p.criteriaHash().matches("[0-9a-f]{64}"))
                || !"alex-de".equals(profiles.get(0).profile().profileId())
                || !"DE".equals(profiles.get(0).profile().workCountry())
                || !"alex-at".equals(profiles.get(1).profile().profileId())
                || !"AT".equals(profiles.get(1).profile().workCountry())) {
            throw new IllegalArgumentException("Scheduled batch requires reviewed DE and AT profiles");
        }
    }

    private void collectGmail(Instant deadline) {
        try {
            var result = gmail.runUntil(deadline);
            LOGGER.info("Scheduled Gmail collection: active={}, more_pages={}, restart_required={}, "
                            + "seen={}, review={}, applied={}, outstanding={}",
                    result.active(), result.morePages(), result.windowRestartRequired(),
                    result.seen(), result.review(), result.applied(), result.outstanding());
        } catch (GmailAccessException failure) {
            if (Thread.currentThread().isInterrupted()) {
                throw failure;
            }
            LOGGER.warn("Scheduled Gmail collection deferred: {}", failure.getMessage());
        }
    }

    private void markInvocationFailed(LocalDate date) {
        try {
            repository.finishInvocation(date, "FAILED");
        } catch (RuntimeException trackingFailure) {
            LOGGER.warn("Scheduled invocation status could not be recorded; inspect the database before retrying");
        }
    }
}
