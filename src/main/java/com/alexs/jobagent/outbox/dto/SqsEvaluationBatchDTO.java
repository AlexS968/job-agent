package com.alexs.jobagent.outbox.dto;

public record SqsEvaluationBatchDTO(int evaluated, int duplicates, int stale, int failed) { }
