package com.alexs.jobagent.source.dto;

public record IngestionResultDTO(long runId, boolean sourceCreated, boolean vacancyCreated) {
}
