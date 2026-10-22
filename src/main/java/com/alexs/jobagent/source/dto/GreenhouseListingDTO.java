package com.alexs.jobagent.source.dto;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

public record GreenhouseListingDTO(String id, String title, String location, String description,
                                   String originalUrl, Instant updatedAt, JsonNode rawPayload) {
}
