package com.alexs.jobagent.outbox.service;

import com.alexs.jobagent.outbox.dto.SqsMessageDTO;
import com.alexs.jobagent.outbox.dto.SqsQueueBacklogDTO;
import java.util.List;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

@Component
@ConditionalOnProperty(name = "jobagent.sqs.enabled", havingValue = "true")
public class AwsSqsGateway implements SqsGateway {
    private final SqsClient client;
    private final String queueUrl;

    public AwsSqsGateway(SqsClient client, @Value("${jobagent.sqs.queue-url}") String queueUrl) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalArgumentException("SQS queue URL is required");
        }
        URI uri = URI.create(queueUrl);
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme())
                && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())))) {
            throw new IllegalArgumentException("SQS queue URL must use HTTPS or local test HTTP");
        }
        this.client = client;
        this.queueUrl = queueUrl;
    }

    @Override
    public void send(String body) {
        client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build());
    }

    @Override
    public List<SqsMessageDTO> receive() {
        return client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
                        .maxNumberOfMessages(1).waitTimeSeconds(10).build()).messages().stream()
                .map(message -> new SqsMessageDTO(message.body(), message.receiptHandle())).toList();
    }

    @Override
    public void delete(String receiptHandle) {
        client.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(receiptHandle).build());
    }

    @Override
    public SqsQueueBacklogDTO backlog() {
        var attributes = client.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED).build()).attributes();
        return new SqsQueueBacklogDTO(
                Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)),
                Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)),
                Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED)));
    }
}
