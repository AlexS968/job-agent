package com.alexs.jobagent.outbox.entity;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventEntity(UUID eventId, String eventType, int schemaVersion, long aggregateId,
                                Instant occurredAt, long runId, String payload, UUID claimToken,
                                int cycleAttempts) {
}
