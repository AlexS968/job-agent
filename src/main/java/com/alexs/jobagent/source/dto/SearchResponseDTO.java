package com.alexs.jobagent.source.dto;

import java.util.List;

public record SearchResponseDTO(List<SearchResultDTO> results, String requestId, Integer billedCredits) {}
