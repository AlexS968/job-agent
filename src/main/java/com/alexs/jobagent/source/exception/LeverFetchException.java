package com.alexs.jobagent.source.exception;

import java.io.IOException;

public class LeverFetchException extends IOException {
    private final int requests;

    public LeverFetchException(String message, int requests, Throwable cause) {
        super(message, cause);
        this.requests = requests;
    }

    public int requests() {
        return requests;
    }
}
