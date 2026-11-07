package com.alexs.jobagent.batch.service;

import com.alexs.jobagent.batch.dto.BatchPipelineResultDTO;
import com.alexs.jobagent.evaluation.service.EvaluationService;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.service.RuleEvaluationPolicy;
import com.alexs.jobagent.outbox.service.OutboxService;
import com.alexs.jobagent.outbox.service.SqsEvaluationConsumerService;
import com.alexs.jobagent.outbox.service.SqsGateway;
import com.alexs.jobagent.outbox.service.SqsProducerService;
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
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Batch pipeline requires no ambient transaction");
        }
        if (profile == null || maxEvents < 1 || maxEvents > 100 || duration.isNegative()
                || duration.isZero() || duration.compareTo(Duration.ofMinutes(15)) > 0
                || shortlistLimit < 1 || shortlistLimit > 20) {
            throw new IllegalArgumentException("Invalid batch pipeline bounds");
        }
        Instant deadline = clock.instant().plus(duration);
        var imported = sources.importActive();
        int published = 0;
        int evaluated = 0;
        int duplicates = 0;
        int stale = 0;
        int failed = imported.failures();
        if (failed == 0) {
            while (published < maxEvents && clock.instant().isBefore(deadline)) {
                var sent = producer.publish(Math.min(10, maxEvents - published));
                published += sent.processed();
                failed += sent.failed() + sent.deferred();
                if (failed > 0 || sent.processed() == 0) {
                    break;
                }
            }
        }
        if (failed == 0) {
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
        }
        var pending = outbox.backlog();
        var queued = queue.backlog();
        var shortlist = evaluations.shortlist(profile, shortlistLimit, RuleEvaluationPolicy.VERSION);
        boolean remaining = pending.ready() + pending.futureRetries() + pending.activeClaims()
                + pending.expiredClaims() + pending.terminalFailures()
                + queued.visible() + queued.inFlight() + queued.delayed() > 0;
        String outcome = failed > 0 ? "FAILED" : !clock.instant().isBefore(deadline) ? "TIME_LIMIT"
                : remaining ? "BACKLOG_REMAINS" : imported.created() + imported.updated() == 0
                && published + evaluated == 0 ? "NO_NEW_VACANCIES" : "QUIESCENT_SNAPSHOT";
        return new BatchPipelineResultDTO(outcome, imported, published, evaluated, duplicates, stale, failed,
                pending, queued, shortlist);
    }
}
