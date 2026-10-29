package com.alexs.jobagent.evaluation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alexs.jobagent.evaluation.dto.ExtractionInputDTO;
import com.alexs.jobagent.evaluation.repository.ExtractionRepository;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

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

    @Test
    void completeRequestBoundIncludesPromptSchemaAndUtf8Bytes() {
        var json = new JsonMapper();
        var gateway = new AnthropicExtractionGateway(json);
        var estimator = new ConservativeExtractionCostEstimator(gateway);
        var inputs = mock(ExtractionInputService.class);
        var service = new ExtractionPilotService(inputs, mock(ExtractionOutputValidator.class), gateway,
                mock(ExtractionRepository.class), json, mock(PlatformTransactionManager.class),
                estimator, new ExtractionCancellationGuard());
        var formerlyOversized = inputWithRequestBytes(gateway, 12_001, "x");
        assertEquals(12_001, gateway.requestBody(formerlyOversized, ExtractionPolicy.PRIMARY_MODEL).length);
        when(inputs.load(1)).thenReturn(formerlyOversized);
        assertEquals(formerlyOversized, service.plan(1, false));
        assertEquals(ExtractionPolicy.cost(ExtractionPolicy.PRIMARY_MODEL, 12_001 * 2 + 8_192,
                ExtractionPolicy.MAX_OUTPUT_TOKENS),
                estimator.estimatedMicroUsd(formerlyOversized, ExtractionPolicy.PRIMARY_MODEL));

        var boundary = inputWithRequestBytes(gateway, 24_000, "x");
        when(inputs.load(1)).thenReturn(boundary);
        assertEquals(boundary, service.plan(1, false));
        assertTrue(estimator.estimatedMicroUsd(boundary, ExtractionPolicy.PRIMARY_MODEL) > 0);
        var oversized = inputWithRequestBytes(gateway, 24_001, "x");
        when(inputs.load(1)).thenReturn(oversized);
        assertThrows(IllegalArgumentException.class, () -> service.plan(1, false));
        assertThrows(IllegalArgumentException.class,
                () -> estimator.estimatedMicroUsd(oversized, ExtractionPolicy.PRIMARY_MODEL));

        var unicode = new ExtractionInputDTO(1, 2, "a".repeat(64),
                Map.of("title", "Synthetic role", "description", "☃".repeat(4_000)));
        byte[] request = gateway.requestBody(unicode, ExtractionPolicy.PRIMARY_MODEL);
        assertEquals(request.length, new String(request, StandardCharsets.UTF_8)
                .getBytes(StandardCharsets.UTF_8).length);
        assertTrue(request.length > new String(request, StandardCharsets.UTF_8).length());
        assertTrue(request.length > 12_000);
        assertTrue(request.length <= ExtractionPolicy.MAX_REQUEST_BYTES);
        assertTrue(request.length > unicode.sourceFields().get("description").getBytes(StandardCharsets.UTF_8).length);
        var tooManyUtf8Bytes = new ExtractionInputDTO(1, 2, "a".repeat(64),
                Map.of("title", "Synthetic role", "description", "☃".repeat(7_000)));
        byte[] oversizedUnicode = gateway.requestBody(tooManyUtf8Bytes, ExtractionPolicy.PRIMARY_MODEL);
        assertTrue(oversizedUnicode.length > ExtractionPolicy.MAX_REQUEST_BYTES);
        assertTrue(new String(oversizedUnicode, StandardCharsets.UTF_8).length()
                < ExtractionPolicy.MAX_REQUEST_BYTES);
        when(inputs.load(1)).thenReturn(tooManyUtf8Bytes);
        assertThrows(IllegalArgumentException.class, () -> service.plan(1, false));
        assertThrows(IllegalArgumentException.class,
                () -> estimator.estimatedMicroUsd(tooManyUtf8Bytes, ExtractionPolicy.PRIMARY_MODEL));
    }

    private static ExtractionInputDTO inputWithRequestBytes(AnthropicExtractionGateway gateway,
                                                           int bytes, String repeated) {
        var base = new ExtractionInputDTO(1, 2, "a".repeat(64),
                Map.of("title", "Synthetic role", "description", ""));
        int overhead = gateway.requestBody(base, ExtractionPolicy.PRIMARY_MODEL).length;
        var input = new ExtractionInputDTO(1, 2, "a".repeat(64),
                Map.of("title", "Synthetic role", "description", repeated.repeat(bytes - overhead)));
        assertEquals(bytes, gateway.requestBody(input, ExtractionPolicy.PRIMARY_MODEL).length);
        return input;
    }
}
