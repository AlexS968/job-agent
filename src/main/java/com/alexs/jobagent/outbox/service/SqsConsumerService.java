package com.alexs.jobagent.outbox.service;

import com.alexs.jobagent.outbox.dto.SqsBatchResultDTO;
import com.alexs.jobagent.outbox.dto.SqsEventDTO;
import com.alexs.jobagent.outbox.dto.SqsMessageDTO;
import com.alexs.jobagent.outbox.dto.SqsStatusDTO;
import com.alexs.jobagent.outbox.entity.OutboxEventEntity;
import com.alexs.jobagent.outbox.repository.ConsumerDeliveryRepository;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
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
public class SqsConsumerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqsConsumerService.class);
    private static final String HANDLER_ID = "sqs-vacancy-diagnostic-v1";
    private final SqsGateway gateway;
    private final OutboxService outbox;
    private final ConsumerDeliveryRepository repository;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SqsConsumerService(SqsGateway gateway, OutboxService outbox, ConsumerDeliveryRepository repository,
                              JsonMapper json, Clock clock, PlatformTransactionManager manager) {
        this.gateway = gateway;
        this.outbox = outbox;
        this.repository = repository;
        this.json = json;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    public SqsBatchResultDTO consume(int limit) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SQS receive requires no ambient transaction");
        }
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("SQS consume limit must be between 1 and 20");
        }
        int processed = 0;
        int duplicates = 0;
        int failed = 0;
        for (int i = 0; i < limit; i++) {
            var messages = gateway.receive();
            if (messages.isEmpty()) {
                break;
            }
            SqsMessageDTO message = messages.getFirst();
            try {
                if (message.body() == null || message.body().getBytes(StandardCharsets.UTF_8).length > 4096) {
                    throw new IllegalArgumentException("Invalid SQS envelope size");
                }
                SqsEventDTO envelope = json.readValue(message.body(), SqsEventDTO.class);
                OutboxEventEntity stored = validStoredEvent(envelope);
                boolean inserted = transaction.execute(status -> {
                    if (repository.recordDeliveryIfAbsent(HANDLER_ID, stored.eventId(), clock.instant())) {
                        repository.observeSqs(stored.eventId(), stored.aggregateId(), clock.instant());
                        return true;
                    }
                    return false;
                });
                gateway.delete(message.receiptHandle());
                LOGGER.info("SQS diagnostic event committed: event_id={}, duplicate={}", stored.eventId(), !inserted);
                if (inserted) {
                    processed++;
                } else {
                    duplicates++;
                }
            } catch (RuntimeException failure) {
                // Leave the message for SQS visibility expiry and bounded DLQ redrive.
                failed++;
            }
        }
        return new SqsBatchResultDTO(processed, duplicates, 0, failed);
    }

    public SqsStatusDTO status(UUID eventId) {
        return repository.sqsStatus(eventId);
    }

    private OutboxEventEntity validStoredEvent(SqsEventDTO envelope) {
        if (envelope == null || envelope.eventId() == null || envelope.aggregateId() <= 0
                || envelope.runId() <= 0 || envelope.occurredAt() == null
                || !("VacancyDiscovered".equals(envelope.eventType())
                     || "VacancyUpdated".equals(envelope.eventType())) || envelope.schemaVersion() != 1) {
            throw new IllegalArgumentException("Unsupported SQS event envelope");
        }
        OutboxEventEntity stored = outbox.findById(envelope.eventId());
        if (stored == null || !SqsEventDTO.from(stored).equals(envelope)) {
            throw new IllegalArgumentException("SQS event does not match persisted outbox");
        }
        var payload = json.readTree(stored.payload());
        if (payload == null || !payload.path("vacancy_id").isIntegralNumber()
                || payload.path("vacancy_id").asLong() != stored.aggregateId()) {
            throw new IllegalArgumentException("Persisted SQS event payload identity is invalid");
        }
        return stored;
    }
}
