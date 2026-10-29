package com.alexs.jobagent.evaluation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alexs.jobagent.evaluation.dto.ExtractionInputDTO;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtractionPolicyTest {
    @Test
    void reportedCacheAndOutputUsageUsesConservativeModelRates() {
        assertEquals(19, ExtractionPolicy.cost(ExtractionPolicy.PRIMARY_MODEL, 3, 2, 3, 5));
        assertEquals(70, ExtractionPolicy.cost(ExtractionPolicy.COMPARISON_MODEL, 3, 2, 3, 5));
        assertEquals(ExtractionPolicy.cost(ExtractionPolicy.PRIMARY_MODEL, 3, 0, 0, 5),
                ExtractionPolicy.cost(ExtractionPolicy.PRIMARY_MODEL, 3, 5));
    }

    @Test
    void unsupportedModelOrNegativeUsageCannotBeAccountedAsZero() {
        assertThrows(IllegalArgumentException.class,
                () -> ExtractionPolicy.cost("unsupported-model", 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ExtractionPolicy.cost(ExtractionPolicy.PRIMARY_MODEL, 1, -1, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ExtractionPolicy.cost(ExtractionPolicy.COMPARISON_MODEL, 1, 0, 0, -1));
    }

    @Test
    void requestBeyondByteLimitCannotBeEstimatedForAdmission() {
        AnthropicExtractionGateway gateway = mock(AnthropicExtractionGateway.class);
        when(gateway.requestBody(any(), eq(ExtractionPolicy.PRIMARY_MODEL)))
                .thenReturn(new byte[ExtractionPolicy.MAX_REQUEST_BYTES + 1]);
        var estimator = new ConservativeExtractionCostEstimator(gateway);
        var input = new ExtractionInputDTO(1, 2, "a".repeat(64), Map.of("title", "Synthetic role"));
        assertThrows(IllegalArgumentException.class,
                () -> estimator.estimatedMicroUsd(input, ExtractionPolicy.PRIMARY_MODEL));
    }
}
