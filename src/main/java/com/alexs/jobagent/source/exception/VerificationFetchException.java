package com.alexs.jobagent.source.exception;

public final class VerificationFetchException extends Exception {
    private final String reason;
    private final int requests;

    public VerificationFetchException(String reason, int requests) {
        super("Source verification failed: " + reason);
        this.reason = reason;
        this.requests = requests;
    }

    public String reason() { return reason; }
    public int requests() { return requests; }
}
