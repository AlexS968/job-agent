package com.alexs.jobagent.batch.service;

import com.alexs.jobagent.batch.dto.BatchPipelineResultDTO;
import com.alexs.jobagent.evaluation.service.EvaluationService;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.service.RuleEvaluationPolicy;
import com.alexs.jobagent.outbox.service.OutboxService;
import com.alexs.jobagent.outbox.service.SqsEvaluationConsumerService;
import com.alexs.jobagent.outbox.service.SqsGateway;
import com.alexs.jobagent.outbox.service.SqsProducerService;
import com.alexs.jobagent.outbox.dto.SqsBatchResultDTO;
import com.alexs.jobagent.outbox.dto.SqsEvaluationBatchDTO;
import com.alexs.jobagent.outbox.dto.BacklogDTO;
import com.alexs.jobagent.outbox.dto.SqsQueueBacklogDTO;
import com.alexs.jobagent.source.dto.ActiveSourceImportResultDTO;
import com.alexs.jobagent.evaluation.dto.ShortlistDTO;
import com.alexs.jobagent.source.service.SourceRegistryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One explicit bounded invocation; each component retains its own transaction boundary. */
@Service
@ConditionalOnProperty(name = "jobagent.sqs.enabled", havingValue = "true")
public class BatchPipelineService {
    private final SourceRegistryService sources;
    private final OutboxService outbox;
    private final SqsProducerService producer;
    private final SqsEvaluationConsumerService consumer;
    private final SqsGateway queue;
    private final EvaluationService evaluations;
    private final Clock clock;

    public BatchPipelineService(SourceRegistryService sources, OutboxService outbox,
                                SqsProducerService producer, SqsEvaluationConsumerService consumer,
                                SqsGateway queue, EvaluationService evaluations, Clock clock) {
        this.sources = sources;
        this.outbox = outbox;
        this.producer = producer;
        this.consumer = consumer;
        this.queue = queue;
        this.evaluations = evaluations;
        this.clock = clock;
    }

    public BatchPipelineResultDTO run(LoadedProfileDTO profile, int maxEvents, Duration duration, int shortlistLimit) {
        BatchPipelineResultDTO processed = runWithoutShortlist(profile, maxEvents, duration, shortlistLimit);
        ShortlistDTO shortlist = evaluations.shortlist(profile, shortlistLimit, RuleEvaluationPolicy.VERSION);
        return new BatchPipelineResultDTO(processed.outcome(), processed.imported(), processed.published(),
                processed.evaluated(), processed.duplicates(), processed.stale(), processed.failed(),
                processed.outbox(), processed.queue(), shortlist);
    }

    /** Scheduled processing defers shortlist selection until every profile has been evaluated. */
    public BatchPipelineResultDTO runWithoutShortlist(LoadedProfileDTO profile, int maxEvents,
                                                      Duration duration, int shortlistLimit) {
        validateArguments(profile, maxEvents, duration, shortlistLimit);
        Instant deadline = clock.instant().plus(duration);
        var imported = sources.importActive();
        SqsBatchResultDTO publication = new SqsBatchResultDTO(0, 0, 0, 0);
        SqsEvaluationBatchDTO consumption = new SqsEvaluationBatchDTO(0, 0, 0, 0);
        if (imported.failures() == 0) {
            publication = publishBatches(maxEvents, deadline);
            if (publication.failed() == 0 && publication.deferred() == 0) {
                consumption = consumeBatches(profile, maxEvents, deadline);
            }
        }
        var pending = outbox.backlog();
        var queued = queue.backlog();
        int failures = imported.failures() + publication.failed() + publication.deferred() + consumption.failed();
        String outcome = outcome(imported, publication, consumption, failures, pending, queued, deadline);
        return new BatchPipelineResultDTO(outcome, imported, publication.processed(), consumption.evaluated(),
                consumption.duplicates(), consumption.stale(), failures, pending, queued,
                new ShortlistDTO(java.util.List.of(), java.util.List.of()));
    }

    /** Read-only report snapshot for a profile after its independent evaluation sweep. */
    public BatchPipelineResultDTO snapshot(LoadedProfileDTO profile, int shortlistLimit) {
        var pending = outbox.backlog();
        var queued = queue.backlog();
        ShortlistDTO shortlist = evaluations.reportShortlist(profile, shortlistLimit, RuleEvaluationPolicy.VERSION);
        boolean remaining = pending.ready() + pending.futureRetries() + pending.activeClaims()
                + pending.expiredClaims() + pending.terminalFailures()
                + queued.visible() + queued.inFlight() + queued.delayed() > 0;
        return new BatchPipelineResultDTO(remaining ? "BACKLOG_REMAINS" : "EVALUATION_SWEEP",
                new ActiveSourceImportResultDTO(0, 0, 0, 0, 0),
                0, 0, 0, 0, 0, pending, queued, shortlist);
    }

    private static void validateArguments(LoadedProfileDTO profile, int maxEvents, Duration duration,
                                          int shortlistLimit) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Batch pipeline requires no ambient transaction");
        }
        if (profile == null || maxEvents < 1 || maxEvents > 100 || duration == null || duration.isNegative()
                || duration.isZero() || duration.compareTo(Duration.ofMinutes(15)) > 0
                || shortlistLimit < 1 || shortlistLimit > 20) {
            throw new IllegalArgumentException("Invalid batch pipeline bounds");
        }
    }

    private SqsBatchResultDTO publishBatches(int maxEvents, Instant deadline) {
        int published = 0;
        int deferred = 0;
        int failed = 0;
        while (published < maxEvents && clock.instant().isBefore(deadline)) {
            var sent = producer.publish(Math.min(10, maxEvents - published));
            published += sent.processed();
            deferred += sent.deferred();
            failed += sent.failed();
            if (deferred > 0 || failed > 0 || sent.processed() == 0) {
                break;
            }
        }
        return new SqsBatchResultDTO(published, 0, deferred, failed);
    }

    private SqsEvaluationBatchDTO consumeBatches(LoadedProfileDTO profile, int maxEvents, Instant deadline) {
        int evaluated = 0;
        int duplicates = 0;
        int stale = 0;
        int failed = 0;
        int handled = 0;
        while (handled < maxEvents && clock.instant().isBefore(deadline)) {
            var batch = consumer.consume(Math.min(10, maxEvents - handled), profile);
            int count = batch.evaluated() + batch.duplicates() + batch.stale() + batch.failed();
            evaluated += batch.evaluated();
            duplicates += batch.duplicates();
            stale += batch.stale();
            failed += batch.failed();
            handled += count;
            if (failed > 0 || count == 0) {
                break;
            }
        }
        return new SqsEvaluationBatchDTO(evaluated, duplicates, stale, failed);
    }

    private String outcome(ActiveSourceImportResultDTO imported, SqsBatchResultDTO publication,
                           SqsEvaluationBatchDTO consumption, int failures, BacklogDTO pending,
                           SqsQueueBacklogDTO queued, Instant deadline) {
        if (failures > 0) {
            return "FAILED";
        }
        if (!clock.instant().isBefore(deadline)) {
            return "TIME_LIMIT";
        }
        int remaining = pending.ready() + pending.futureRetries() + pending.activeClaims()
                + pending.expiredClaims() + pending.terminalFailures()
                + queued.visible() + queued.inFlight() + queued.delayed();
        if (remaining > 0) {
            return "BACKLOG_REMAINS";
        }
        if (imported.created() + imported.updated() == 0
                && publication.processed() + consumption.evaluated() == 0) {
            return "NO_NEW_VACANCIES";
        }
        return "QUIESCENT_SNAPSHOT";
    }
}
