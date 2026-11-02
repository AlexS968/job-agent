package com.alexs.jobagent.outbox.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SqsConfigTest {
    private final SqsConfig config = new SqsConfig();

    @Test
    void acceptsOnlyTlsOrExplicitLocalEmulatorEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> config.sqsClient("http://example.org:9324"));
        assertThrows(IllegalArgumentException.class, () -> config.sqsClient("ftp://localhost:9324"));
        assertThrows(IllegalArgumentException.class, () -> config.sqsClient("http://127.0.0.2:9324"));
        try (var local = config.sqsClient("http://127.0.0.1:9324");
             var localName = config.sqsClient("http://localhost:9324");
             var tls = config.sqsClient("https://sqs.us-east-1.amazonaws.com");
             var defaultClient = config.sqsClient("")) {
            assertDoesNotThrow(() -> local.serviceName());
            assertDoesNotThrow(() -> localName.serviceName());
            assertDoesNotThrow(() -> tls.serviceName());
            assertDoesNotThrow(() -> defaultClient.serviceName());
        }
    }
}
