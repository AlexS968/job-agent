package com.alexs.jobagent.outbox.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;

class AwsSqsGatewayTest {
    private final SqsClient client = mock(SqsClient.class);

    @Test
    void rejectsMissingAndInsecureRemoteQueueUrlsBeforeSending() {
        assertThrows(IllegalArgumentException.class, () -> new AwsSqsGateway(client, null));
        assertThrows(IllegalArgumentException.class, () -> new AwsSqsGateway(client, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new AwsSqsGateway(client, "http://example.org/queue/events"));
        assertThrows(IllegalArgumentException.class,
                () -> new AwsSqsGateway(client, "ftp://localhost/queue/events"));
        assertNotNull(new AwsSqsGateway(client, "https://sqs.us-east-1.amazonaws.com/123/events"));
        assertNotNull(new AwsSqsGateway(client, "http://localhost:9324/queue/events"));
        assertNotNull(new AwsSqsGateway(client, "http://127.0.0.1:9324/queue/events"));
    }
}
