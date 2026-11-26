package com.alexs.jobagent.evaluation.dto;

/** Progress for a bounded projection refresh; no evaluation or external action is performed. */
public record EvaluationInputRefreshDTO(int examined, int refreshed, int unavailable,
                                        long lastVacancyId, boolean moreAvailable) { }
