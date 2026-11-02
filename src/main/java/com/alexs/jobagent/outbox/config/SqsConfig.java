package com.alexs.jobagent.outbox.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class SqsConfig {
    @Bean
    @ConditionalOnProperty(name = "jobagent.sqs.enabled", havingValue = "true")
    SqsClient sqsClient(@Value("${jobagent.sqs.endpoint:}") String endpoint) {
        var builder = SqsClient.builder().region(Region.US_EAST_1)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(60))
                        .apiCallAttemptTimeout(Duration.ofSeconds(30)).build());
        if (!endpoint.isBlank()) {
            URI uri = URI.create(endpoint);
            if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme())
                    && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())))) {
                throw new IllegalArgumentException("SQS endpoint must use HTTPS or local test HTTP");
            }
            builder.endpointOverride(uri);
            if ("http".equals(uri.getScheme())) {
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("synthetic-local", "synthetic-local")));
            }
        }
        return builder.build();
    }
}
