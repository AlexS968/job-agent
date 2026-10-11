package com.alexs.jobagent.outbox.dto;

import com.alexs.jobagent.outbox.entity.OutboxEventEntity;

public record ClaimResultDTO(OutboxEventEntity event, int exhaustedClaims) {
}
