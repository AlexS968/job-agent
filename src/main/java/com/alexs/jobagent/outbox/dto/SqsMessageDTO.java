package com.alexs.jobagent.outbox.dto;

public record SqsMessageDTO(String body, String receiptHandle) {
}
