package com.alexs.jobagent.evaluation.dto;

public record ExtractionUsageDTO(Integer inputTokens, Integer cacheCreationTokens, Integer cacheReadTokens,
                                 Integer outputTokens, Long observedCostMicroUsd, String requestId) { }
