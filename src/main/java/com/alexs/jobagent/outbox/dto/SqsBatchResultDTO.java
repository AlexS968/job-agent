package com.alexs.jobagent.outbox.dto;

public record SqsBatchResultDTO(int processed, int duplicates, int deferred, int failed) {
}
