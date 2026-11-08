package com.alexs.jobagent.batch.service;

import com.alexs.jobagent.batch.repository.ScheduledBatchRepository;
import com.alexs.jobagent.batch.dto.BatchPipelineResultDTO;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.service.EvaluationService;
import com.alexs.jobagent.evaluation.service.RuleEvaluationPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
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
    private final EvaluationService evaluations;
    private final BatchReportFormatter formatter;
    private final ReportGateway gateway;
    private final Clock clock;

    public ScheduledBatchService(ScheduledBatchRepository repository, BatchPipelineService pipeline,
                                 EvaluationService evaluations, BatchReportFormatter formatter,
                                 ReportGateway gateway, Clock clock) {
        this.repository = repository;
        this.pipeline = pipeline;
        this.evaluations = evaluations;
        this.formatter = formatter;
        this.gateway = gateway;
        this.clock = clock;
    }

    /** One Berlin-date invocation; report and SNS acknowledgement state is independent by profile. */
    public void run(List<LoadedProfileDTO> profiles) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Scheduled batch requires no ambient transaction");
        }
        requireProfiles(profiles);
        LocalDate date = LocalDate.now(clock.withZone(BERLIN));
        if (!repository.startInvocation(date)) {
            if ("COMPLETE".equals(repository.invocationStatus(date))) {
                LOGGER.info("Scheduled batch slot {} already completed; no processing or report replay", date);
                return;
            }
            throw new IllegalStateException("Scheduled batch requires manual review of active or uncertain work");
        }
        Instant deadline = clock.instant().plus(Duration.ofMinutes(24));
        try {
            List<String> prior = profiles.stream().map(profile -> repository.profileStatus(date,
                    profile.profile().profileId(), profile.profile().version(), profile.criteriaHash())).toList();
            if (prior.stream().anyMatch(status -> status != null
                    && !"FAILED".equals(status) && !"PUBLISHED".equals(status))) {
                throw new IllegalStateException("Scheduled profile requires manual review");
            }
            BatchPipelineResultDTO base = null;
            if (!"PUBLISHED".equals(prior.getFirst())) {
                base = pipeline.runWithoutShortlist(profiles.getFirst(), 50, Duration.ofMinutes(10), 20);
                if ("FAILED".equals(base.outcome()) || "TIME_LIMIT".equals(base.outcome())) {
                    throw new IllegalStateException("Batch pipeline failed or reached its time limit");
                }
            }
            int[] created = new int[profiles.size()];
            for (int index = 0; index < profiles.size(); index++) {
                if (!"PUBLISHED".equals(prior.get(index))) {
                    created[index] = evaluateAll(profiles.get(index), deadline);
                }
            }
            for (int index = 0; index < profiles.size(); index++) {
                if (!"PUBLISHED".equals(prior.get(index))) {
                    runProfile(date, profiles.get(index), prior.get(index), index == 0 ? base : null,
                            created[index]);
                }
            }
            repository.finishInvocation(date, "COMPLETE");
        } catch (RuntimeException failure) {
            markInvocationFailed(date);
            throw failure;
        }
    }

    private void runProfile(LocalDate date, LoadedProfileDTO profile, String prior,
                            BatchPipelineResultDTO base, int created) {
        String id = profile.profile().profileId();
        if (prior == null) {
            if (!repository.start(date, id, profile.profile().version(), profile.criteriaHash())) {
                throw new IllegalStateException("Scheduled profile slot changed unexpectedly");
            }
        } else if (!"FAILED".equals(prior)
                || !repository.retryFailedProfile(date, id, profile.profile().version(), profile.criteriaHash())) {
            throw new IllegalStateException("Scheduled profile requires manual review");
        }
        try {
            BatchPipelineResultDTO snapshot = pipeline.snapshot(profile, 20);
            BatchPipelineResultDTO metrics = base == null ? snapshot : base;
            BatchPipelineResultDTO result = new BatchPipelineResultDTO(metrics.outcome(), metrics.imported(),
                    metrics.published(), metrics.evaluated() + created, metrics.duplicates(), metrics.stale(),
                    metrics.failed(), snapshot.outbox(), snapshot.queue(), snapshot.shortlist());
            String body = formatter.format(date, profile, result);
            repository.transition(date, id, "RUNNING", "PREPARED", body, null);
            repository.transition(date, id, "PREPARED", PUBLISHING, null, null);
            publishPrepared(date, id, formatter.subject(date, profile, result), body);
            LOGGER.info("Scheduled report: date={}, profile={}, status=PUBLISHED, outcome={}",
                    date, id, result.outcome());
        } catch (RuntimeException failure) {
            markIfPossible(date, id, "RUNNING", "FAILED");
            throw failure;
        }
    }

    private int evaluateAll(LoadedProfileDTO profile, Instant deadline) {
        long after = 0;
        int created = 0;
        boolean more;
        do {
            if (!clock.instant().isBefore(deadline)) {
                throw new IllegalStateException("Scheduled evaluation reached its time limit");
            }
            var page = evaluations.evaluate(profile, after, 500, RuleEvaluationPolicy.VERSION);
            created += page.created();
            after = page.lastVacancyId();
            more = page.moreAvailable();
        } while (more);
        return created;
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

    private void publishPrepared(LocalDate date, String profileId, String subject, String body) {
        String messageId;
        try {
            messageId = gateway.publish(subject, body);
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalStateException("SNS did not return a message identity");
            }
        } catch (RuntimeException uncertain) {
            markIfPossible(date, profileId, PUBLISHING, "UNCERTAIN");
            throw new IllegalStateException("Report publication outcome is uncertain; review before any replay", uncertain);
        }
        repository.transition(date, profileId, PUBLISHING, "PUBLISHED", null, messageId);
    }

    private void markIfPossible(LocalDate date, String profileId, String from, String to) {
        try {
            repository.transition(date, profileId, from, to, null, null);
        } catch (RuntimeException trackingFailure) {
            // Preserve the original error. A DB outage can also prevent status recording.
            LOGGER.warn("Scheduled profile status could not be recorded; inspect the database before retrying");
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
