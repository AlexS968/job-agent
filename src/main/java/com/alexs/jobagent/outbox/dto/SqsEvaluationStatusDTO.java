package com.alexs.jobagent.outbox.dto;

import com.alexs.jobagent.evaluation.dto.EvaluationStatusDTO;

public record SqsEvaluationStatusDTO(String outcome, EvaluationStatusDTO evaluation) { }
