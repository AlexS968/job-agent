package com.alexs.jobagent.source.dto;

import java.util.UUID;

public record DiscoveryClaimDTO(long id, String candidateUrl, UUID token) { }
