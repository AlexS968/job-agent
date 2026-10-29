package com.alexs.jobagent.evaluation.exception;

public class ExtractionValidationException extends IllegalArgumentException {
    private final int claimIndex;
    private final String category;

    public ExtractionValidationException(int claimIndex, String category) {
        super("Extraction validation failed: claim_index=" + claimIndex + ", category=" + category);
        this.claimIndex = claimIndex;
        this.category = category;
    }

    public int claimIndex() {
        return claimIndex;
    }

    public String category() {
        return category;
    }
}
