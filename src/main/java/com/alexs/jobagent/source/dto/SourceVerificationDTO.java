package com.alexs.jobagent.source.dto;

import java.time.Instant;

public record SourceVerificationDTO(String inputUrl, String origin, String officialDomain,
                                    String officialPageUrl, String observedUrl, Instant observedAt,
                                    String outcome, String reason, AtsSourceIdentityDTO identity,
                                    String employerName, int requests, String evidenceHash) { }
