package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.ExtractionInputDTO;
import org.springframework.stereotype.Service;

@Service
public final class ConservativeExtractionCostEstimator implements ExtractionCostEstimator {
    private static final int INJECTED_PROMPT_MARGIN_TOKENS = 8_192;
    private final AnthropicExtractionGateway gateway;

    public ConservativeExtractionCostEstimator(AnthropicExtractionGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public long estimatedMicroUsd(ExtractionInputDTO input, String model) {
        byte[] completeRequest = gateway.requestBody(input, model);
        if (completeRequest.length > ExtractionPolicy.MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Extraction request exceeds byte bound");
        }
        // Byte count is a conservative local proxy, not a guaranteed provider token count.
        int estimatedInputTokens = Math.addExact(Math.multiplyExact(completeRequest.length, 2),
                INJECTED_PROMPT_MARGIN_TOKENS);
        return ExtractionPolicy.cost(model, estimatedInputTokens, ExtractionPolicy.MAX_OUTPUT_TOKENS);
    }
}
