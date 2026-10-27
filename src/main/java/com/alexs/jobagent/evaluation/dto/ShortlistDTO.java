package com.alexs.jobagent.evaluation.dto;

import java.util.List;

public record ShortlistDTO(List<VacancyEvaluationDTO> suitable, List<VacancyEvaluationDTO> needsReview) { }
