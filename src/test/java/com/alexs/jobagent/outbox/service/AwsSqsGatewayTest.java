package com.alexs.jobagent.outbox.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.util.Map;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import org.mockito.ArgumentCaptor;

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

    @Test
    void readsAllThreeQueueBacklogStatesWithoutTreatingMissingAsZero() {
        var gateway = new AwsSqsGateway(client, "https://sqs.us-east-1.amazonaws.com/123/events");
        when(client.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenReturn(
                GetQueueAttributesResponse.builder().attributes(Map.of(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "4",
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "2",
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED, "1")).build());
        var backlog = gateway.backlog();
        assertEquals(4, backlog.visible());
        assertEquals(2, backlog.inFlight());
        assertEquals(1, backlog.delayed());
        var request = ArgumentCaptor.forClass(GetQueueAttributesRequest.class);
        verify(client).getQueueAttributes(request.capture());
        assertEquals("https://sqs.us-east-1.amazonaws.com/123/events", request.getValue().queueUrl());
        assertEquals(3, request.getValue().attributeNames().size());
        when(client.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenReturn(
                GetQueueAttributesResponse.builder().attributes(Map.of()).build());
        assertThrows(IllegalStateException.class, gateway::backlog);
    }
}
