package com.alexs.jobagent.source.exception;

import java.io.IOException;

public class GreenhouseFetchException extends IOException {
    private final int attempts;

    public GreenhouseFetchException(String message, int attempts, Throwable cause) {
        super(message, cause);
        this.attempts = attempts;
    }

    public int attempts() {
        return attempts;
    }
}
