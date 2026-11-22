package com.alexs.jobagent.source.exception;

public class InvalidGmailPageTokenException extends IllegalStateException {
    public InvalidGmailPageTokenException() {
        super("Gmail rejected the continuation token");
    }
}
