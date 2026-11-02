package com.alexs.jobagent.outbox.service;

import com.alexs.jobagent.outbox.dto.ClaimResultDTO;
import com.alexs.jobagent.outbox.dto.SqsBatchResultDTO;
import com.alexs.jobagent.outbox.entity.OutboxEventEntity;
import com.alexs.jobagent.outbox.dto.SqsEventDTO;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "jobagent.sqs.enabled", havingValue = "true")
public class SqsProducerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqsProducerService.class);
    private final OutboxService outbox;
    private final SqsGateway gateway;
    private final JsonMapper json;

    public SqsProducerService(OutboxService outbox, SqsGateway gateway, JsonMapper json) {
        this.outbox = outbox;
        this.gateway = gateway;
        this.json = json;
    }

    public SqsBatchResultDTO publish(int limit) {
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("SQS publish limit must be between 1 and 20");
        }
        String worker = "sqs-" + UUID.randomUUID();
        int sent = 0;
        int deferred = 0;
        int failed = 0;
        for (int i = 0; i < limit; i++) {
            ClaimResultDTO claim = outbox.claimWithRecovery(worker);
            failed += claim.exhaustedClaims();
            OutboxEventEntity event = claim.event();
            if (event == null) {
                if (claim.exhaustedClaims() == 0) {
                    break;
                }
            } else {
                PublishOutcome outcome = sendClaim(event);
                if (outcome == PublishOutcome.SENT) {
                    sent++;
                } else if (outcome == PublishOutcome.FAILED) {
                    failed++;
                } else {
                    deferred++;
                }
            }
        }
        return new SqsBatchResultDTO(sent, 0, deferred, failed);
    }

    private PublishOutcome sendClaim(OutboxEventEntity event) {
        String body = json.writeValueAsString(SqsEventDTO.from(event));
        try {
            gateway.send(body);
        } catch (RuntimeException sendFailure) {
            if (!outbox.deferOrFail(event, "SQS_SEND_UNCERTAIN")) {
                throw new IllegalStateException("SQS send outcome and outbox claim require review", sendFailure);
            }
            return event.cycleAttempts() >= outbox.maxAttempts() ? PublishOutcome.FAILED : PublishOutcome.DEFERRED;
        }
        if (!outbox.markPublished(event)) {
            throw new IllegalStateException("SQS accepted event but outbox acknowledgement was lost");
        }
        LOGGER.info("Outbox event published to SQS: event_id={}", event.eventId());
        return PublishOutcome.SENT;
    }

    private enum PublishOutcome { SENT, DEFERRED, FAILED }
}
