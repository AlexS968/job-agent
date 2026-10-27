package com.alexs.jobagent.source.dto;

public record SourceEvidenceDTO(long sourceItemId, String originalUrl, String rawPayload,
                                String inputVersion, String normalizationVersion) { }
