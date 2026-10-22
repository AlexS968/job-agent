package com.alexs.jobagent.source.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

final class SourceRetryPolicy {
    private SourceRetryPolicy() {
    }

    static Duration delay(String retryAfter, int attempt, Duration remaining, Clock clock)
            throws BudgetExceededException {
        if (retryAfter != null) {
            String header = retryAfter.trim();
            if (header.matches("\\d+")) {
                try {
                    return withinBudget(Duration.ofSeconds(Long.parseLong(header)), remaining);
                } catch (NumberFormatException tooLarge) {
                    throw new BudgetExceededException();
                }
            }
            try {
                Instant requestedAt = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Instant now = clock.instant();
                if (!requestedAt.isAfter(now)) {
                    return Duration.ZERO;
                }
                if (requestedAt.isAfter(now.plus(remaining))) {
                    throw new BudgetExceededException();
                }
                return Duration.between(now, requestedAt);
            } catch (DateTimeParseException malformed) {
                // A malformed header uses bounded local backoff.
            }
        }
        return fallback(attempt, remaining);
    }

    static Duration fallback(int attempt, Duration remaining) throws BudgetExceededException {
        return withinBudget(Duration.ofMillis(200L * attempt), remaining);
    }

    private static Duration withinBudget(Duration delay, Duration remaining) throws BudgetExceededException {
        if (delay.compareTo(remaining) > 0) {
            throw new BudgetExceededException();
        }
        return delay;
    }

    static final class BudgetExceededException extends Exception {
    }
}
