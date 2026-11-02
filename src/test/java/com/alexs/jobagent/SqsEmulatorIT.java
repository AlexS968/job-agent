package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import com.alexs.jobagent.outbox.service.AwsSqsGateway;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

class SqsEmulatorIT {
    @Test
    void visibilityRetryEventuallyMovesUnacknowledgedMessageToDlq() {
        try (GenericContainer<?> emulator = new GenericContainer<>(
                DockerImageName.parse("softwaremill/elasticmq-native:1.7.1")).withExposedPorts(9324)) {
            emulator.start();
            URI endpoint = URI.create("http://" + emulator.getHost() + ":" + emulator.getMappedPort(9324));
            try (SqsClient client = SqsClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("synthetic", "synthetic"))).build()) {
                String dlq = client.createQueue(CreateQueueRequest.builder().queueName("synthetic-dlq").build()).queueUrl();
                String dlqArn = client.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(dlq)
                        .attributeNames(QueueAttributeName.QUEUE_ARN).build()).attributes().get(QueueAttributeName.QUEUE_ARN);
                String source = client.createQueue(CreateQueueRequest.builder().queueName("synthetic-source")
                        .attributesWithStrings(java.util.Map.of("VisibilityTimeout", "1", "RedrivePolicy",
                                "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"2\"}"))
                        .build()).queueUrl();
                String deliveryQueue = client.createQueue(CreateQueueRequest.builder()
                        .queueName("synthetic-gateway").build()).queueUrl();
                var gateway = new AwsSqsGateway(client, deliveryQueue);
                gateway.send("synthetic-envelope");
                var delivered = gateway.receive();
                assertEquals("synthetic-envelope", delivered.getFirst().body());
                gateway.delete(delivered.getFirst().receiptHandle());
                assertTrue(gateway.receive().isEmpty());
                client.sendMessage(SendMessageRequest.builder().queueUrl(source).messageBody("synthetic-invalid").build());
                for (int attempt = 0; attempt < 2; attempt++) {
                    var messages = client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(source)
                            .waitTimeSeconds(1).maxNumberOfMessages(1).build()).messages();
                    assertEquals(1, messages.size());
                    client.changeMessageVisibility(ChangeMessageVisibilityRequest.builder().queueUrl(source)
                            .receiptHandle(messages.getFirst().receiptHandle()).visibilityTimeout(0).build());
                }
                client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(source).waitTimeSeconds(1).build());
                assertEquals(1, client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(dlq)
                        .waitTimeSeconds(1).build()).messages().size());
            }
        }
    }
}
