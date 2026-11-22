package com.alexs.jobagent.source.exception;

public class InvalidGmailPageTokenException extends GmailAccessException {
    public InvalidGmailPageTokenException() {
        super("Gmail rejected the continuation token");
    }
}
