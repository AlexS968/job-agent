package com.alexs.jobagent.outbox.dto;

public record SqsStatusDTO(boolean receiptRecorded, boolean observationRecorded) {
}
