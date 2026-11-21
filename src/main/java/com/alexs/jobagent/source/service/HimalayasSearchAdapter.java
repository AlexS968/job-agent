package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.HimalayasListingDTO;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HimalayasSearchAdapter {
    private static final String HOST = "himalayas.app";
    private static final String SEARCH = "https://" + HOST + "/jobs/api/search?q=Java&sort=recent&page=1";
    private static final int PAGE_SIZE = 20;
    private static final Pattern POSTING_PATH = Pattern.compile("/companies/[a-zA-Z0-9-]+/jobs/[a-zA-Z0-9-]+/?");
    private final BoundedSourceHttpTransport transport;
    private final JsonMapper json;

    public HimalayasSearchAdapter(BoundedSourceHttpTransport transport, JsonMapper json) {
        this.transport = transport;
        this.json = json;
    }

    SearchBatch fetch() {
        int requests = 0;
        int fetched = 0;
        List<HimalayasListingDTO> listings = new ArrayList<>();
        Map<String, String> failedFilters = new LinkedHashMap<>();
        for (Filter filter : Filter.values()) {
            requireNotInterrupted();
            requests++;
            try {
                var response = transport.get(filter.uri);
                if (response.status() != 200) {
                    failedFilters.put(filter.name(), "HTTP_" + response.status());
                } else {
                    fetched += appendPage(response.body(), listings);
                }
            } catch (BoundedSourceHttpTransport.RequestInterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Himalayas search interrupted", interrupted);
            } catch (BoundedSourceHttpTransport.ResponseTooLargeException oversized) {
                failedFilters.put(filter.name(), "RESPONSE_TOO_LARGE");
            } catch (IOException transportFailure) {
                failedFilters.put(filter.name(), "TRANSPORT_FAILURE");
            } catch (IllegalArgumentException malformed) {
                failedFilters.put(filter.name(), "INVALID_RESPONSE");
            }
        }
        return new SearchBatch(requests, fetched, fetched - listings.size(),
                List.copyOf(listings), Collections.unmodifiableMap(failedFilters));
    }

    private int appendPage(byte[] body, List<HimalayasListingDTO> listings) {
        JsonNode jobs;
        try {
            JsonNode root = json.readTree(body);
            jobs = root == null ? null : root.path("jobs");
        } catch (tools.jackson.core.JacksonException malformed) {
            throw new IllegalArgumentException("Invalid Himalayas response");
        }
        if (jobs == null || !jobs.isArray() || jobs.size() > PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid Himalayas page size or shape");
        }
        for (JsonNode row : jobs) {
            try {
                listings.add(listing(row));
            } catch (IllegalArgumentException invalid) {
                // Count invalid rows and keep importing their valid neighbors.
            }
        }
        return jobs.size();
    }

    private static HimalayasListingDTO listing(JsonNode row) {
        String title = required(row.path("title"), 255);
        String guid = required(row.path("guid"), 2048);
        URI uri = URI.create(guid);
        String path = uri.getRawPath();
        if (!"https".equals(uri.getScheme()) || !HOST.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || uri.getPort() != -1 || path == null
                || !POSTING_PATH.matcher(path).matches() || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid Himalayas posting GUID");
        }
        String canonicalPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String postingId = UUID.nameUUIDFromBytes(canonicalPath.getBytes(StandardCharsets.UTF_8)).toString();
        JsonNode description = row.path("description");
        if (!description.isTextual() && !description.isMissingNode() && !description.isNull()) {
            throw new IllegalArgumentException("Invalid Himalayas description");
        }
        String body = description.isTextual() ? description.asText() : null;
        if (body != null && body.length() > 1_000_000) {
            throw new IllegalArgumentException("Invalid Himalayas description");
        }
        return new HimalayasListingDTO(postingId, guid, HimalayasPostingEvidence.applicationLink(row), title,
                advertisedLocation(row.path("locationRestrictions")), body, row);
    }

    private static String advertisedLocation(JsonNode restrictions) {
        if (!restrictions.isArray() || restrictions.isEmpty()) {
            return null;
        }
        var parts = new ArrayList<String>();
        for (JsonNode restriction : restrictions) {
            if (!restriction.isTextual() || restriction.asText().isBlank()) {
                return null;
            }
            parts.add(restriction.asText());
        }
        String location = String.join(", ", parts);
        return location.length() <= 255 ? location : null;
    }

    private static String required(JsonNode field, int limit) {
        if (!field.isTextual() || field.asText().isBlank() || field.asText().length() > limit) {
            throw new IllegalArgumentException("Invalid Himalayas posting field");
        }
        return field.asText();
    }

    private static void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Himalayas search interrupted");
        }
    }

    private enum Filter {
        DE("&country=DE&exclude_worldwide=true"),
        AT("&country=AT&exclude_worldwide=true"),
        WORLDWIDE("&worldwide=true");

        private final URI uri;

        Filter(String query) {
            this.uri = URI.create(SEARCH + query);
        }
    }

    record SearchBatch(int requests, int fetched, int invalidRows, List<HimalayasListingDTO> listings,
                       Map<String, String> failedFilters) { }
}
