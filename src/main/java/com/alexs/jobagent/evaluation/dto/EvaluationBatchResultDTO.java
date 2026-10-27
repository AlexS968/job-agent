package com.alexs.jobagent.evaluation.dto;

public record EvaluationBatchResultDTO(int examined, int created, int alreadyCurrent,
                                       long lastVacancyId, boolean moreAvailable) { }
