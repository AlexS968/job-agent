package com.alexs.jobagent.source.dto;

import java.time.Instant;
import java.util.List;

public record LeverFetchResultDTO(List<LeverListingDTO> listings, int requests, Instant observationStartedAt) {
}
