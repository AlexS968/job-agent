package com.alexs.jobagent.source.entity;

import java.time.Instant;

public record VerifiedSourceEntity(long id, String platform, String region, String sourceId,
                                   String employerDomain, String employerName, String officialPageUrl,
                                   String evidenceUrl, String evidenceHash, Instant verifiedAt,
                                   String status, boolean active) { }
