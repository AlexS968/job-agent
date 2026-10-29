package com.alexs.jobagent.evaluation.dto;

public record ExtractionGatewayResponseDTO(String content, int inputTokens, int cacheCreationTokens,
                                           int cacheReadTokens, int outputTokens, String requestId) {
    public ExtractionGatewayResponseDTO(String content, int inputTokens, int outputTokens, String requestId) {
        this(content, inputTokens, 0, 0, outputTokens, requestId);
    }
}
