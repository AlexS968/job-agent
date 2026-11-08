package com.alexs.jobagent.evaluation.dto;

import java.util.List;

public record ShortlistDTO(List<VacancyEvaluationDTO> suitable, List<VacancyEvaluationDTO> needsReview,
                           int totalSuitable, int totalNeedsReview) {
    public ShortlistDTO(List<VacancyEvaluationDTO> suitable, List<VacancyEvaluationDTO> needsReview) {
        this(suitable, needsReview, suitable.size(), needsReview.size());
    }
}
