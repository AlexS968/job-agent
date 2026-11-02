package com.alexs.jobagent.outbox.dto;

import com.alexs.jobagent.outbox.entity.OutboxEventEntity;
import java.time.Instant;
import java.util.UUID;

public record SqsEventDTO(UUID eventId, String eventType, int schemaVersion, long aggregateId,
                          Instant occurredAt, long runId) {
    public static SqsEventDTO from(OutboxEventEntity event) {
        return new SqsEventDTO(event.eventId(), event.eventType(), event.schemaVersion(),
                event.aggregateId(), event.occurredAt(), event.runId());
    }
}
