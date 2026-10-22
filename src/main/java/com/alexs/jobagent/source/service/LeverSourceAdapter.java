package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.LeverFetchResultDTO;
import com.alexs.jobagent.source.dto.LeverListingDTO;
import com.alexs.jobagent.source.dto.LeverSiteDTO;
import com.alexs.jobagent.source.exception.LeverFetchException;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class LeverSourceAdapter implements LeverPostingSource {
    private static final Pattern SITE = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 4;
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_WAIT_BUDGET = Duration.ofSeconds(4);
    private final BoundedSourceHttpTransport transport;
    private final JsonMapper json;
    private final Clock clock;
    private final Sleeper sleeper;

    @Autowired
    public LeverSourceAdapter(BoundedSourceHttpTransport transport, JsonMapper json, Clock clock) {
        this(transport, json, clock, duration -> Thread.sleep(duration.toMillis()));
    }

    LeverSourceAdapter(BoundedSourceHttpTransport transport, JsonMapper json, Clock clock, Sleeper sleeper) {
        this.transport = transport;
        this.json = json;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public LeverFetchResultDTO fetch(LeverSiteDTO site) throws IOException {
        validateSite(site);
        Instant observationStartedAt = clock.instant();
        Duration remainingWait = RETRY_WAIT_BUDGET;
        List<LeverListingDTO> listings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int requests = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            URI uri = URI.create("https://" + apiHost(site.region()) + "/v0/postings/" + site.site()
                    + "?mode=json&skip=" + (page * PAGE_SIZE) + "&limit=" + PAGE_SIZE);
            BoundedSourceHttpTransport.Response response;
            int attempt = 0;
            while (true) {
                attempt++;
                requests++;
                try {
                    response = transport.get(uri);
                } catch (BoundedSourceHttpTransport.ResponseTooLargeException tooLarge) {
                    throw new LeverFetchException("Lever response exceeds size limit", requests, tooLarge);
                } catch (IOException transportFailure) {
                    if (attempt == MAX_ATTEMPTS) {
                        throw new LeverFetchException("Lever transport exhausted", requests, transportFailure);
                    }
                    remainingWait = pause(null, attempt, requests, remainingWait);
                    continue;
                }
                if (response.status() == 200) {
                    break;
                }
                if (retryable(response.status()) && attempt < MAX_ATTEMPTS) {
                    remainingWait = pause(response.firstHeader("Retry-After"), attempt, requests, remainingWait);
                    continue;
                }
                throw new LeverFetchException("Lever returned HTTP " + response.status(), requests, null);
            }
            List<LeverListingDTO> current;
            try {
                current = parse(response.body(), site);
            } catch (IOException malformed) {
                throw new LeverFetchException("Malformed Lever response", requests, malformed);
            }
            for (LeverListingDTO listing : current) {
                if (!seen.add(listing.id())) {
                    throw new LeverFetchException("Lever pagination repeated a posting", requests, null);
                }
                listings.add(listing);
            }
            if (current.size() < PAGE_SIZE) {
                return new LeverFetchResultDTO(List.copyOf(listings), requests, observationStartedAt);
            }
        }
        throw new LeverFetchException("Lever pagination limit reached", requests, null);
    }

    private Duration pause(String retryAfter, int attempt, int requests, Duration remaining) throws IOException {
        Duration delay;
        try {
            delay = SourceRetryPolicy.delay(retryAfter, attempt, remaining, clock);
        } catch (SourceRetryPolicy.BudgetExceededException exhausted) {
            throw new LeverFetchException("Lever Retry-After exceeds retry budget", requests, null);
        }
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LeverFetchException("Lever retry interrupted", requests, interrupted);
        }
        return remaining.minus(delay);
    }

    private List<LeverListingDTO> parse(byte[] body, LeverSiteDTO site) throws IOException {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isArray() || root.size() > PAGE_SIZE) {
                throw new IllegalArgumentException("Invalid Lever postings collection");
            }
            List<LeverListingDTO> result = new ArrayList<>();
            for (JsonNode posting : root) {
                if (!posting.isObject()) {
                    throw new IllegalArgumentException("Invalid Lever posting");
                }
                String id = required(posting.get("id"), 120);
                String title = required(posting.get("text"), 255);
                String hostedUrl = required(posting.get("hostedUrl"), 2048);
                checkHostedUrl(hostedUrl, site);
                String applyUrl = optional(posting.get("applyUrl"), 2048);
                if (applyUrl != null) {
                    checkHostedUrl(applyUrl, site);
                }
                JsonNode categories = posting.get("categories");
                if (categories != null && !categories.isObject()) {
                    throw new IllegalArgumentException("Invalid Lever categories");
                }
                String location = categories == null ? null : optional(categories.get("location"), 255);
                String workplace = optional(posting.get("workplaceType"), 40);
                String description = optional(posting.get("description"), 1_000_000);
                JsonNode lists = posting.get("lists");
                if (lists != null && !lists.isArray()) {
                    throw new IllegalArgumentException("Invalid Lever lists");
                }
                result.add(new LeverListingDTO(id, title, location, workplace, description,
                        hostedUrl, applyUrl, posting));
            }
            return List.copyOf(result);
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed Lever response", malformed);
        }
    }

    static void validateSite(LeverSiteDTO site) {
        if (site == null || !("global".equals(site.region()) || "eu".equals(site.region()))
                || site.site() == null || !SITE.matcher(site.site()).matches()
                || site.employerName() == null || site.employerName().isBlank()
                || site.employerName().length() > 255) {
            throw new IllegalArgumentException("Invalid Lever site configuration");
        }
    }

    static String apiHost(String region) {
        return "eu".equals(region) ? "api.eu.lever.co" : "api.lever.co";
    }

    private static boolean retryable(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    static void checkHostedUrl(String value, LeverSiteDTO site) {
        URI uri = URI.create(value);
        String expectedHost = "eu".equals(site.region()) ? "jobs.eu.lever.co" : "jobs.lever.co";
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !expectedHost.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || !uri.getPath().startsWith("/" + site.site() + "/")) {
            throw new IllegalArgumentException("Invalid Lever hosted URL");
        }
    }

    private static String required(JsonNode value, int max) {
        String text = optional(value, max);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Missing Lever required field");
        }
        return text;
    }

    private static String optional(JsonNode value, int max) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Invalid Lever field type");
        }
        String text = value.asText();
        if (text.length() > max) {
            throw new IllegalArgumentException("Lever field exceeds size limit");
        }
        return text;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
