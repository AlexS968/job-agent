package com.alexs.jobagent.batch.service;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AwsSnsReportGatewayTest {
    private static final String TOPIC = "arn:aws:sns:us-east-1:123456789012:synthetic-report";

    @Test
    void rejectsInvalidTopicBeforeCreatingClient() {
        assertThrows(IllegalArgumentException.class, () -> new AwsSnsReportGateway(null));
        assertThrows(IllegalArgumentException.class,
                () -> new AwsSnsReportGateway("arn:aws:sns:eu-central-1:123456789012:synthetic-report"));
    }

    @Test
    void realConfigurationConstructsAndClosesWithoutARequest() {
        try (var gateway = new AwsSnsReportGateway(TOPIC)) {
            // Client construction must not load credentials or contact SNS.
        }
    }

    @Test
    void publishesOnlyTheConfiguredTopicAndReturnsProviderIdentity() {
        SnsClient client = mock(SnsClient.class);
        when(client.publish(any(PublishRequest.class)))
                .thenReturn(PublishResponse.builder().messageId("synthetic-message-id").build());
        var gateway = new AwsSnsReportGateway(TOPIC, client);
        assertEquals("synthetic-message-id", gateway.publish("Batch result", "Synthetic report"));
        var request = org.mockito.ArgumentCaptor.forClass(PublishRequest.class);
        verify(client).publish(request.capture());
        assertEquals(TOPIC, request.getValue().topicArn());
        assertEquals("Batch result", request.getValue().subject());
        assertEquals("Synthetic report", request.getValue().message());
        gateway.close();
        verify(client).close();
    }

    @Test
    void providerFailurePropagatesWithoutAnotherPublish() {
        SnsClient client = mock(SnsClient.class);
        when(client.publish(any(PublishRequest.class)))
                .thenThrow(SnsException.builder().message("synthetic timeout").build());
        var gateway = new AwsSnsReportGateway(TOPIC, client);
        assertThrows(SnsException.class, () -> gateway.publish("Batch result", "Synthetic report"));
        verify(client).publish(any(PublishRequest.class));
    }
}
