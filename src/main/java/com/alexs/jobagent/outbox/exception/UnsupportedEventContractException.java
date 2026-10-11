package com.alexs.jobagent.outbox.exception;

public class UnsupportedEventContractException extends RuntimeException {
    public UnsupportedEventContractException() {
        super("Unsupported event contract");
    }
}
