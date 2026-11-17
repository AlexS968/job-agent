package com.alexs.jobagent.source.exception;

public final class VerificationFetchException extends Exception {
    private final String reason;
    private final int requests;
    private final Integer httpStatus;
    private final int redirects;

    public VerificationFetchException(String reason, int requests) {
        this(reason, requests, null, 0);
    }

    public VerificationFetchException(String reason, int requests, Integer httpStatus, int redirects) {
        super("Source verification failed: " + reason);
        this.reason = reason;
        this.requests = requests;
        this.httpStatus = httpStatus;
        this.redirects = redirects;
    }

    public String reason() { return reason; }
    public int requests() { return requests; }
    public Integer httpStatus() { return httpStatus; }
    public int redirects() { return redirects; }
    public boolean retryable() { return httpStatus == null || httpStatus == 429 || httpStatus >= 500; }
}
