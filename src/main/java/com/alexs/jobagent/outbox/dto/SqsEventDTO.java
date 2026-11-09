package com.alexs.jobagent.outbox.dto;

import java.time.Instant;
import java.util.UUID;

public record SqsEventDTO(UUID eventId, String eventType, int schemaVersion, long aggregateId,
                          Instant occurredAt, long runId) {
}
