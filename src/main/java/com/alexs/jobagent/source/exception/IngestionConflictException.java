package com.alexs.jobagent.source.exception;

public class IngestionConflictException extends RuntimeException {
    public IngestionConflictException(String message) {
        super(message);
    }
}
