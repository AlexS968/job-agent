package com.alexs.jobagent.source.dto;

import tools.jackson.databind.JsonNode;

public record LeverListingDTO(String id, String title, String location, String workplaceType,
                              String description, String hostedUrl, String applyUrl, JsonNode rawPayload) {
}
