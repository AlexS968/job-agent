package com.alexs.jobagent.source.dto;

public record DiscoveryBatchDTO(boolean enabled, int queries, int attempts, int leads,
                                int confirmed, int activated, int review, int retryable,
                                int failures, boolean monthlyLimitReached) { }
