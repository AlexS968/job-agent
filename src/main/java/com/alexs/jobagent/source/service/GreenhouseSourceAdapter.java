package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.GreenhouseBoardDTO;
import com.alexs.jobagent.source.dto.GreenhouseFetchResultDTO;
import com.alexs.jobagent.source.dto.GreenhouseListingDTO;
import com.alexs.jobagent.source.exception.GreenhouseFetchException;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GreenhouseSourceAdapter implements VacancySourceAdapter {
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_WAIT_BUDGET = Duration.ofSeconds(4);
    private static final int MAX_LISTINGS = 300;
    private final GreenhouseHttpTransport transport;
    private final JsonMapper json;
    private final Clock clock;
    private final Sleeper sleeper;

    @Autowired
    public GreenhouseSourceAdapter(GreenhouseHttpTransport transport, JsonMapper json, Clock clock) {
        this(transport, json, clock, duration -> Thread.sleep(duration.toMillis()));
    }

    GreenhouseSourceAdapter(GreenhouseHttpTransport transport, JsonMapper json, Clock clock, Sleeper sleeper) {
        this.transport = transport;
        this.json = json;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public GreenhouseFetchResultDTO fetch(GreenhouseBoardDTO board) throws IOException {
        if (board == null || board.token() == null || !TOKEN.matcher(board.token()).matches()
                || board.employerName() == null || board.employerName().isBlank()
                || board.employerName().length() > 255) {
            throw new IllegalArgumentException("Invalid Greenhouse board configuration");
        }
        URI uri = URI.create("https://boards-api.greenhouse.io/v1/boards/" + board.token() + "/jobs?content=true");
        Duration remainingWait = RETRY_WAIT_BUDGET;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            GreenhouseHttpTransport.Response response;
            try {
                response = transport.get(uri);
            } catch (GreenhouseHttpTransport.ResponseTooLargeException tooLarge) {
                throw new GreenhouseFetchException("Greenhouse response exceeds size limit", attempt, tooLarge);
            } catch (IOException transportFailure) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new GreenhouseFetchException("Greenhouse transport exhausted", attempt, transportFailure);
                }
                Duration delay = withinBudget(Duration.ofMillis(200L * attempt), remainingWait, attempt);
                pause(delay, attempt);
                remainingWait = remainingWait.minus(delay);
                continue;
            }
            if (response.status() == 200) {
                try {
                    return new GreenhouseFetchResultDTO(parse(response.body()), attempt);
                } catch (IOException malformed) {
                    throw new GreenhouseFetchException("Malformed Greenhouse response", attempt, malformed);
                }
            }
            if ((response.status() == 429 || response.status() == 500 || response.status() == 502
                    || response.status() == 503 || response.status() == 504) && attempt < MAX_ATTEMPTS) {
                Duration delay = retryDelay(response.firstHeader("Retry-After"), attempt, remainingWait);
                pause(delay, attempt);
                remainingWait = remainingWait.minus(delay);
                continue;
            }
            throw new GreenhouseFetchException("Greenhouse returned HTTP " + response.status(), attempt, null);
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private void pause(Duration delay, int attempt) throws IOException {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GreenhouseFetchException("Greenhouse retry interrupted", attempt, interrupted);
        }
    }

    private Duration retryDelay(String value, int attempt, Duration remainingWait) throws GreenhouseFetchException {
        if (value != null) {
            String header = value.trim();
            if (header.matches("[0-9]+")) {
                try {
                    return withinBudget(Duration.ofSeconds(Long.parseLong(header)), remainingWait, attempt);
                } catch (NumberFormatException tooLarge) {
                    throw new GreenhouseFetchException("Greenhouse Retry-After exceeds retry budget", attempt, null);
                }
            }
            try {
                Instant when = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Instant now = clock.instant();
                if (!when.isAfter(now)) {
                    return Duration.ZERO;
                }
                if (when.isAfter(now.plus(remainingWait))) {
                    throw new GreenhouseFetchException("Greenhouse Retry-After exceeds retry budget", attempt, null);
                }
                return Duration.between(now, when);
            } catch (java.time.format.DateTimeParseException malformed) {
                // An invalid advisory header falls back to the local bounded retry delay.
            }
        }
        return withinBudget(Duration.ofMillis(200L * attempt), remainingWait, attempt);
    }

    private static Duration withinBudget(Duration delay, Duration remaining, int attempt)
            throws GreenhouseFetchException {
        if (delay.compareTo(remaining) > 0) {
            throw new GreenhouseFetchException("Greenhouse Retry-After exceeds retry budget", attempt, null);
        }
        return delay;
    }

    private List<GreenhouseListingDTO> parse(byte[] body) throws IOException {
        try {
            JsonNode root = json.readTree(body);
            JsonNode jobs = root == null ? null : root.get("jobs");
            if (jobs == null || !jobs.isArray() || jobs.size() > MAX_LISTINGS) {
                throw new IllegalArgumentException("Invalid Greenhouse jobs collection");
            }
            List<GreenhouseListingDTO> listings = new ArrayList<>();
            for (JsonNode job : jobs) {
                if (!job.isObject()) {
                    throw new IllegalArgumentException("Invalid Greenhouse listing");
                }
                String id = required(job.get("id"), 40);
                if (!id.matches("[0-9]+")) {
                    throw new IllegalArgumentException("Invalid Greenhouse post identity");
                }
                String title = required(job.get("title"), 255);
                String url = required(job.get("absolute_url"), 2048);
                URI original = URI.create(url);
                if (!("https".equalsIgnoreCase(original.getScheme())
                        || "http".equalsIgnoreCase(original.getScheme())) || original.getHost() == null
                        || original.getUserInfo() != null) {
                    throw new IllegalArgumentException("Invalid Greenhouse source URL");
                }
                JsonNode locationNode = job.get("location");
                String location = optional(locationNode == null ? null : locationNode.get("name"), 255);
                String description = optional(job.get("content"), 1_000_000);
                String updated = optional(job.get("updated_at"), 80);
                Instant updatedAt = updated == null ? null : OffsetDateTime.parse(updated).toInstant();
                listings.add(new GreenhouseListingDTO(id, title, location, description,
                        url, updatedAt, job));
            }
            return List.copyOf(listings);
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed Greenhouse response", malformed);
        }
    }

    private static String required(JsonNode value, int max) {
        String text = optional(value, max);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Missing Greenhouse required field");
        }
        return text;
    }

    private static String optional(JsonNode value, int max) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() && !value.isNumber()) {
            throw new IllegalArgumentException("Invalid Greenhouse field type");
        }
        String text = value.asText();
        if (text.length() > max) {
            throw new IllegalArgumentException("Greenhouse field exceeds size limit");
        }
        return text;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
