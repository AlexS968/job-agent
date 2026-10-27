package com.alexs.jobagent.evaluation.dto;

import java.util.List;

public record VacancyEvaluationDTO(long vacancyId, long sourceItemId, String vacancyRevision,
                                   String decision, int preferenceRank, String roleClassification,
                                   List<String> reasons, List<String> unknowns, List<String> evidence,
                                   String originalUrl) { }
