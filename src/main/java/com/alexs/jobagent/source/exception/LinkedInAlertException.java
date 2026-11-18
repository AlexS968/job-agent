package com.alexs.jobagent.source.exception;

public class LinkedInAlertException extends IllegalArgumentException {
    private final String reason;

    public LinkedInAlertException(String reason) {
        super("LinkedIn alert requires review: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
