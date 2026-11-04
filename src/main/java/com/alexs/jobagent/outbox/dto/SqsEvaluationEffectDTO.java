package com.alexs.jobagent.outbox.dto;

public record SqsEvaluationEffectDTO(String outcome, Long evaluationId) { }
