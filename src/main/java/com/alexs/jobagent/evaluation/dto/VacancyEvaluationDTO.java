package com.alexs.jobagent.evaluation.dto;

import java.util.List;

public record VacancyEvaluationDTO(long vacancyId, long sourceItemId, String vacancyRevision,
                                   String decision, int preferenceRank, String roleClassification,
                                   List<String> reasons, List<String> unknowns, List<String> evidence,
                                   String originalUrl, Long extractionWorkId, String extractionStatus,
                                   int rejectedCount, String title) {
    public VacancyEvaluationDTO(long vacancyId, long sourceItemId, String vacancyRevision,
                                String decision, int preferenceRank, String roleClassification,
                                List<String> reasons, List<String> unknowns, List<String> evidence,
                                String originalUrl) {
        this(vacancyId, sourceItemId, vacancyRevision, decision, preferenceRank, roleClassification,
                reasons, unknowns, evidence, originalUrl, null, "NONE", 0, null);
    }

    public VacancyEvaluationDTO withEvidence(List<String> newReasons, List<String> newUnknowns,
                                             List<String> newEvidence, Long workId, String status,
                                             int rejected) {
        return new VacancyEvaluationDTO(vacancyId, sourceItemId, vacancyRevision, decision, preferenceRank,
                roleClassification, List.copyOf(newReasons), List.copyOf(newUnknowns),
                List.copyOf(newEvidence), originalUrl, workId, status, rejected, title);
    }
}
