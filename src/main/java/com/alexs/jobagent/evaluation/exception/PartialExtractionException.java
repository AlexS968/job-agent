package com.alexs.jobagent.evaluation.exception;

public class PartialExtractionException extends IllegalStateException {
    public PartialExtractionException() {
        super("Extraction contains rejected claims");
    }
}
