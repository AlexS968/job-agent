package com.alexs.jobagent.outbox.service;

import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.service.EvaluationService;
import com.alexs.jobagent.outbox.dto.SqsEvaluationBatchDTO;
import com.alexs.jobagent.outbox.dto.SqsEventDTO;
import com.alexs.jobagent.outbox.dto.SqsMessageDTO;
import com.alexs.jobagent.outbox.dto.SqsEvaluationStatusDTO;
import com.alexs.jobagent.outbox.repository.ConsumerDeliveryRepository;
import com.alexs.jobagent.outbox.mapper.SqsEventMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "jobagent.sqs.enabled", havingValue = "true")
public class SqsEvaluationConsumerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqsEvaluationConsumerService.class);
    private static final String HANDLER_PREFIX = "sqs-vacancy-evaluation-rules-v1-";
    private final SqsGateway gateway;
    private final OutboxService outbox;
    private final ConsumerDeliveryRepository deliveries;
    private final EvaluationService evaluations;
    private final JsonMapper json;
    private final SqsEventMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SqsEvaluationConsumerService(SqsGateway gateway, OutboxService outbox,
                                        ConsumerDeliveryRepository deliveries, EvaluationService evaluations,
                                        JsonMapper json, Clock clock, PlatformTransactionManager manager,
                                        SqsEventMapper mapper) {
        this.gateway = gateway;
        this.outbox = outbox;
        this.deliveries = deliveries;
        this.evaluations = evaluations;
        this.json = json;
        this.mapper = mapper;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    public SqsEvaluationBatchDTO consume(int limit, LoadedProfileDTO profile) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SQS receive requires no ambient transaction");
        }
        if (limit < 1 || limit > 20 || profile == null || profile.profile() == null
                || profile.criteriaHash() == null || !profile.criteriaHash().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SQS evaluation configuration");
        }
        String handler = handler(profile);
        int evaluated = 0;
        int duplicates = 0;
        int stale = 0;
        int failed = 0;
        for (int i = 0; i < limit; i++) {
            var messages = gateway.receive();
            if (messages.isEmpty()) {
                break;
            }
            try {
                switch (process(messages.getFirst(), profile, handler)) {
                    case EVALUATED -> evaluated++;
                    case DUPLICATE -> duplicates++;
                    case STALE -> stale++;
                }
            } catch (RuntimeException failure) {
                // A failed database transaction leaves no receipt. A failed delete is safe to redeliver.
                failed++;
            }
        }
        return new SqsEvaluationBatchDTO(evaluated, duplicates, stale, failed);
    }

    public SqsEvaluationStatusDTO status(UUID eventId, LoadedProfileDTO profile) {
        if (eventId == null || profile == null) {
            throw new IllegalArgumentException("Evaluation status requires an event and profile");
        }
        var effect = deliveries.evaluationEffect(handler(profile), eventId);
        if (effect == null) {
            throw new IllegalArgumentException("No evaluation effect for this event and profile");
        }
        if (effect.evaluationId() == null) {
            return new SqsEvaluationStatusDTO(effect.outcome(), null);
        }
        var evaluation = evaluations.evaluationStatus(effect.evaluationId());
        if (evaluation == null || !evaluation.profileId().equals(profile.profile().profileId())
                || !evaluation.profileVersion().equals(profile.profile().version())
                || !evaluation.criteriaHash().equals(profile.criteriaHash())) {
            throw new IllegalStateException("Evaluation status identity mismatch");
        }
        return new SqsEvaluationStatusDTO(effect.outcome(), evaluation);
    }

    private static String handler(LoadedProfileDTO profile) {
        if (profile == null || profile.profile() == null || profile.criteriaHash() == null
                || !profile.criteriaHash().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SQS evaluation profile");
        }
        return HANDLER_PREFIX + profile.criteriaHash();
    }

    private Outcome process(SqsMessageDTO message, LoadedProfileDTO profile, String handler) {
        if (message.body() == null || message.body().getBytes(StandardCharsets.UTF_8).length > 4096) {
            throw new IllegalArgumentException("Invalid SQS envelope size");
        }
        SqsEventDTO envelope = json.readValue(message.body(), SqsEventDTO.class);
        var stored = SqsEventValidation.stored(envelope, outbox, json, mapper, true);
        var payload = json.readTree(stored.payload());
        long sourceId = payload.path("source_item_id").asLong();
        String revision = payload.path("vacancy_revision").asText();
        Outcome outcome = transaction.execute(status -> {
            if (!deliveries.recordDeliveryIfAbsent(handler, stored.eventId(), clock.instant())) {
                return Outcome.DUPLICATE;
            }
            var effect = evaluations.evaluateEventInTransaction(stored.aggregateId(), sourceId, revision, profile);
            String state = effect.stale() ? "STALE" : "EVALUATED";
            deliveries.recordEvaluationEffect(handler, stored.eventId(), state, effect.evaluationId(), clock.instant());
            return effect.stale() ? Outcome.STALE : Outcome.EVALUATED;
        });
        gateway.delete(message.receiptHandle());
        LOGGER.info("SQS evaluation event committed: event_id={}, outcome={}", stored.eventId(), outcome);
        return outcome;
    }

    private enum Outcome { EVALUATED, DUPLICATE, STALE }
}
