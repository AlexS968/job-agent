package com.alexs.jobagent.outbox.service;

import com.alexs.jobagent.outbox.entity.OutboxEventEntity;

public record ClaimResult(OutboxEventEntity event, int exhaustedClaims) {
}
