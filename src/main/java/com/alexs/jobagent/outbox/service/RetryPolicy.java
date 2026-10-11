package com.alexs.jobagent.outbox.service;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class RetryPolicy {
    public int maxAttempts() {
        return 3;
    }

    public Duration backoff(int cycleAttempts) {
        return Duration.ofSeconds(Math.min(60, 1 << Math.min(cycleAttempts - 1, 6)));
    }
}
