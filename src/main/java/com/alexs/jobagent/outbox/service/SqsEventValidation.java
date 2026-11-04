package com.alexs.jobagent.outbox.service;

import com.alexs.jobagent.outbox.dto.SqsEventDTO;
import com.alexs.jobagent.outbox.entity.OutboxEventEntity;
import tools.jackson.databind.json.JsonMapper;

final class SqsEventValidation {
    private SqsEventValidation() { }

    static OutboxEventEntity stored(SqsEventDTO envelope, OutboxService outbox, JsonMapper json,
                                    boolean requireSnapshot) {
        if (envelope == null || envelope.eventId() == null || envelope.aggregateId() <= 0
                || envelope.runId() <= 0 || envelope.occurredAt() == null
                || !("VacancyDiscovered".equals(envelope.eventType())
                     || "VacancyUpdated".equals(envelope.eventType()))
                || envelope.schemaVersion() != 1) {
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
        if (requireSnapshot && (!payload.path("source_item_id").isIntegralNumber()
                || payload.path("source_item_id").asLong() <= 0
                || !payload.path("vacancy_revision").isTextual()
                || !payload.path("vacancy_revision").asText().matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Persisted SQS event revision is invalid");
        }
        return stored;
    }
}
