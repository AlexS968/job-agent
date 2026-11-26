package com.alexs.jobagent.source.service;

import java.net.URI;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HimalayasPostingEvidence {
    private final JsonMapper json;

    public HimalayasPostingEvidence(JsonMapper json) {
        this.json = json;
    }

    public ReviewMetadataDTO reviewMetadata(String rawPayload, Instant now) {
        JsonNode row = json.readTree(rawPayload);
        JsonNode expiry = row.path("expiryDate");
        boolean expired = expiry.isIntegralNumber() && expiry.canConvertToLong()
                && expiry.asLong() > 0 && expiry.asLong() <= now.getEpochSecond();
        return new ReviewMetadataDTO(expired, applicationLink(row));
    }

    public Instant expiry(String rawPayload) {
        JsonNode expiry = json.readTree(rawPayload).path("expiryDate");
        if (!expiry.isIntegralNumber() || !expiry.canConvertToLong() || expiry.asLong() <= 0) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(expiry.asLong());
        } catch (java.time.DateTimeException outsideSupportedRange) {
            return null;
        }
    }

    static String applicationLink(JsonNode row) {
        JsonNode field = row.path("applicationLink");
        if (!field.isTextual() || field.asText().length() > 2048) {
            return null;
        }
        String link = field.asText();
        try {
            URI uri = URI.create(link);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    ? link : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public record ReviewMetadataDTO(boolean expired, String applicationLink) { }
}
