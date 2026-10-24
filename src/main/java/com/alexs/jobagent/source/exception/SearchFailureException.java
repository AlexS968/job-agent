package com.alexs.jobagent.source.exception;

public final class SearchFailureException extends Exception {
    public enum Kind { TRANSIENT, QUOTA, AUTHENTICATION, INVALID_RESPONSE, PERMANENT, CANCELLED }

    private final Kind kind;
    private final String requestId;
    private final String retryAfter;

    public SearchFailureException(Kind kind, String requestId, String retryAfter) {
        super("Search failed: " + kind);
        this.kind = kind;
        this.requestId = requestId;
        this.retryAfter = retryAfter;
    }

    public Kind kind() { return kind; }
    public String requestId() { return requestId; }
    public String retryAfter() { return retryAfter; }
}
