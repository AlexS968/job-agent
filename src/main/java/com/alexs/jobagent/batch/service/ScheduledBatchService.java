package com.alexs.jobagent.batch.service;

import com.alexs.jobagent.batch.dto.PreparedResultDTO;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(name = "jobagent.report.enabled", havingValue = "true")
public class ScheduledBatchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledBatchService.class);
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private final ScheduledBatchRepository repository;
    private final ScheduledResultService results;
    private final BatchPipelineService pipeline;
    private final EvaluationService evaluations;
    private final BatchReportFormatter formatter;
    private final ReportGateway gateway;
    private final Clock clock;
    private final String pageUrl;

    public ScheduledBatchService(ScheduledBatchRepository repository, ScheduledResultService results,
                                 BatchPipelineService pipeline, EvaluationService evaluations,
                                 BatchReportFormatter formatter, ReportGateway gateway, Clock clock,
                                 @Value("${JOBAGENT_REPORT_PAGEURL:}") String pageUrl) {
        this.repository = repository;
        this.results = results;
        this.pipeline = pipeline;
        this.evaluations = evaluations;
        this.formatter = formatter;
        this.gateway = gateway;
        this.clock = clock;
        this.pageUrl = pageUrl;
    }

    /** One Berlin-date slot and one SNS result publication for both profiles. */
    public void run(List<LoadedProfileDTO> profiles) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Scheduled batch requires no ambient transaction");
        }
        requireProfiles(profiles);
        formatter.requirePageUrl(pageUrl);
        LocalDate date = LocalDate.now(clock.withZone(BERLIN));
        if ("COMPLETE".equals(repository.invocationStatus(date))) {
            LOGGER.info("Scheduled batch slot {} already completed; no processing or report replay", date);
            return;
        }
        if (repository.hasLegacyReportOn(date)) {
            throw new IllegalStateException("Historical profile publication occupies this date; review required");
        }
        results.requireNoUnresolvedPublication();
        if (!repository.startInvocation(date)) {
            throw new IllegalStateException("Scheduled batch requires manual review of active work");
        }
        Instant deadline = clock.instant().plus(Duration.ofMinutes(24));
        try {
            if ("PUBLISHED".equals(repository.resultStatus(date))) {
                repository.finishInvocation(date, "COMPLETE");
                return;
            }
            var base = pipeline.runWithoutShortlist(profiles.getFirst(), 50, Duration.ofMinutes(10), 20);
            if ("FAILED".equals(base.outcome()) || "TIME_LIMIT".equals(base.outcome())) {
                throw new IllegalStateException("Batch pipeline failed or reached its time limit");
            }
            evaluateAll(profiles.getFirst(), deadline);
            evaluateAll(profiles.get(1), deadline);
            var deCandidates = evaluations.reportCandidates(profiles.getFirst(), RuleEvaluationPolicy.VERSION);
            var atCandidates = evaluations.reportCandidates(profiles.get(1), RuleEvaluationPolicy.VERSION);
            LOGGER.info("Current country recommendations: de={}, at={}, policy={}; profiles may overlap",
                    deCandidates.size(), atCandidates.size(), RuleEvaluationPolicy.VERSION);
            if (!clock.instant().isBefore(deadline)) {
                throw new IllegalStateException("Scheduled report reached its time limit");
            }
            PreparedResultDTO prepared = results.prepare(date, profiles.getFirst(), profiles.get(1),
                    deCandidates, atCandidates, pageUrl, base);
            results.markPublishing(date);
            publishPrepared(date, prepared);
            repository.finishInvocation(date, "COMPLETE");
            LOGGER.info("Scheduled result: date={}, status=PUBLISHED, new_distinct={}, new_de={}, new_at={}, "
                            + "newly_relevant_distinct={}", date, prepared.distinctNew(),
                    prepared.germanyNew(), prepared.austriaNew(), prepared.distinctRelevant());
        } catch (RuntimeException failure) {
            markInvocationFailed(date);
            throw failure;
        }
    }

    private void evaluateAll(LoadedProfileDTO profile, Instant deadline) {
        long after = 0;
        boolean more;
        do {
            if (!clock.instant().isBefore(deadline)) {
                throw new IllegalStateException("Scheduled evaluation reached its time limit");
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

    private void publishPrepared(LocalDate date, PreparedResultDTO prepared) {
        String messageId;
        try {
            messageId = gateway.publish(prepared.subject(), prepared.body());
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalStateException("SNS did not return a message identity");
            }
        } catch (RuntimeException uncertain) {
            try {
                results.markUncertain(date);
            } catch (RuntimeException trackingFailure) {
                LOGGER.warn("Publication uncertainty could not be recorded; inspect the database before retrying");
            }
            throw new IllegalStateException("Report publication outcome is uncertain; review before replay", uncertain);
        }
        // A failed acknowledgement leaves PUBLISHING in the database; a rerun must not republish.
        results.markPublished(date, messageId);
    }

    private void markInvocationFailed(LocalDate date) {
        try {
            repository.finishInvocation(date, "FAILED");
        } catch (RuntimeException trackingFailure) {
            LOGGER.warn("Scheduled invocation status could not be recorded; inspect the database before retrying");
        }
    }
}
