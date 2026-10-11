package com.alexs.jobagent.source.exception;

public class InvalidIngestionException extends RuntimeException {
    public InvalidIngestionException(String message) {
        super(message);
    }
}
