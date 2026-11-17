package com.alexs.jobagent.source.exception;

public class MonthlySearchLimitException extends IllegalStateException {
    public MonthlySearchLimitException() {
        super("Monthly Basic Search request limit reached; no request sent");
    }
}
