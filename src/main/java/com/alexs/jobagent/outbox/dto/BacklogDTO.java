package com.alexs.jobagent.outbox.dto;

public record BacklogDTO(int ready, int futureRetries, int activeClaims, int expiredClaims, int terminalFailures) {
}
