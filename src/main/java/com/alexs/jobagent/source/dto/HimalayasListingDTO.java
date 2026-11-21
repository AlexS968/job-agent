package com.alexs.jobagent.source.dto;

import tools.jackson.databind.JsonNode;

public record HimalayasListingDTO(String postingId, String postingUrl, String applicationLink, String title,
                                   String advertisedLocation, String description, JsonNode evidence) { }
