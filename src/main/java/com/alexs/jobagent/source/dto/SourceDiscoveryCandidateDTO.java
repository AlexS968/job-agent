package com.alexs.jobagent.source.dto;

import java.time.Instant;

public record SourceDiscoveryCandidateDTO(long id, String candidateUrl, Instant firstSeenAt, Instant lastSeenAt,
                                          String verificationOutcome, String verificationReason,
                                          String officialDomain, AtsSourceIdentityDTO identity) { }
