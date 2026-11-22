package com.alexs.jobagent.source.exception;

/** A Gmail operation failed. Messages contain only safe diagnostics. */
public class GmailAccessException extends IllegalStateException {
    public GmailAccessException(String message) {
        super(message);
    }
}
