package com.alexs.jobagent.evaluation.dto;

public record ExtractionClaimDTO(String fact, String value, String sourceField, String quote,
                                 String uncertainty, SkillEvidenceDTO skillEvidence) {
    public ExtractionClaimDTO(String fact, String value, String sourceField, String quote, String uncertainty) {
        this(fact, value, sourceField, quote, uncertainty, null);
    }
}
