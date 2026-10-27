package com.alexs.jobagent.source.entity;

import java.time.Instant;

public record VerificationDecisionEntity(String employerDomain, Instant observedAt,
                                         String decision, String evidenceHash,
                                         String associatedPlatform, String associatedRegion,
                                         String associatedSourceId) { }
