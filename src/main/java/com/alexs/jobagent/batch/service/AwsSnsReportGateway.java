package com.alexs.jobagent.batch.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

@Component
@ConditionalOnProperty(name = "jobagent.report.enabled", havingValue = "true")
public class AwsSnsReportGateway implements ReportGateway, AutoCloseable {
    private final SnsClient client;
    private final String topicArn;

    public AwsSnsReportGateway(@Value("${jobagent.report.topic-arn}") String topicArn) {
        if (topicArn == null || !topicArn.matches("arn:aws:sns:us-east-1:[0-9]{12}:[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("A us-east-1 report topic ARN is required");
        }
        this.topicArn = topicArn;
        this.client = SnsClient.builder().region(Region.US_EAST_1)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(20))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()).build()).build();
    }

    @Override
    public String publish(String subject, String body) {
        return client.publish(PublishRequest.builder().topicArn(topicArn).subject(subject)
                .message(body).build()).messageId();
    }

    @Override
    public void close() {
        client.close();
    }
}
