package com.alexs.jobagent.evaluation.service;

import java.time.LocalDate;

public final class ExtractionPolicy {
    public static final String PROVIDER = "ANTHROPIC";
    public static final String PRIMARY_MODEL = "claude-haiku-5-5";
    public static final String COMPARISON_MODEL = "claude-sonnet-5-5";
    public static final String PROMPT_VERSION = "evidence-v1";
    public static final String SCHEMA_VERSION = "schema-v1";
    public static final String POLICY_VERSION = "pilot-v1";
    public static final LocalDate PRICE_REVIEW_DATE = LocalDate.of(2026, 10, 29);
    public static final int MAX_REQUEST_BYTES = 12_000;
    public static final int MAX_OUTPUT_TOKENS = 2_048;
    public static final int MAX_RESPONSE_BYTES = 100_000;
    public static final int MAX_ATTEMPTS_PER_WORK = 3;
    public static final long TARGET_MICRO_USD = 2_000_000;
    public static final long AUTHORIZED_CEILING_MICRO_USD = 3_000_000;

    private ExtractionPolicy() { }

    public static long cost(String model, int inputTokens, int outputTokens) {
        return cost(model, inputTokens, 0, 0, outputTokens);
    }

    public static long cost(String model, int inputTokens, int cacheCreationTokens,
                            int cacheReadTokens, int outputTokens) {
        if (inputTokens < 0 || cacheCreationTokens < 0 || cacheReadTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("Invalid provider token usage");
        }
        return switch (model) {
            // Use the higher Haiku tier; cache writes use the maximum 1h multiplier.
            case PRIMARY_MODEL -> (inputTokens + 1L) / 2L + cacheCreationTokens
                    + (cacheReadTokens + 1L) / 2L + (outputTokens * 5L + 1L) / 2L;
            case COMPARISON_MODEL -> inputTokens * 2L + cacheCreationTokens * 4L
                    + cacheReadTokens * 2L + outputTokens * 10L;
            default -> throw new IllegalArgumentException("Unsupported extraction model");
        };
    }
}
