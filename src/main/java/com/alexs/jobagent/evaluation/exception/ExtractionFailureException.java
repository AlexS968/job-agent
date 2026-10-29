package com.alexs.jobagent.evaluation.exception;

public class ExtractionFailureException extends RuntimeException {
    private final String outcome;
    private final Integer inputTokens;
    private final Integer outputTokens;
    private final Integer cacheCreationTokens;
    private final Integer cacheReadTokens;
    private final String requestId;

    public ExtractionFailureException(String outcome, String message) {
        this(outcome, message, null, null, null, null, null);
    }

    public ExtractionFailureException(String outcome, String message, Integer inputTokens,
                                      Integer outputTokens, String requestId) {
        this(outcome, message, inputTokens, 0, 0, outputTokens, requestId);
    }

    public ExtractionFailureException(String outcome, String message, Integer inputTokens,
                                      Integer cacheCreationTokens, Integer cacheReadTokens,
                                      Integer outputTokens, String requestId) {
        super(message);
        this.outcome = outcome;
        this.inputTokens = inputTokens;
        this.cacheCreationTokens = cacheCreationTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.outputTokens = outputTokens;
        this.requestId = requestId;
    }

    public String outcome() {
        return outcome;
    }

    public Integer inputTokens() { return inputTokens; }
    public Integer outputTokens() { return outputTokens; }
    public Integer cacheCreationTokens() { return cacheCreationTokens; }
    public Integer cacheReadTokens() { return cacheReadTokens; }
    public String requestId() { return requestId; }
}
