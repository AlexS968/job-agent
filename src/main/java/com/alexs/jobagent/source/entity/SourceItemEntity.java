package com.alexs.jobagent.source.entity;

import java.time.Instant;

public record SourceItemEntity(String sourceNamespace, String sourceItemId, String listingNamespace,
                               String listingId, String originalUrl, Instant fetchedAt, String rawPayload,
                               String inputVersion, String normalizationVersion, String inputDigest) {
}
