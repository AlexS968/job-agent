package com.alexs.jobagent.source.dto;

import java.util.List;

public record SearchPilotResultDTO(int queries, int attempts, int remaining, int failures,
                                   boolean cancelled, List<String> unverifiedUrls,
                                   boolean monthlyLimitReached) {
    public SearchPilotResultDTO {
        unverifiedUrls = List.copyOf(unverifiedUrls);
    }
}
