package com.alexs.jobagent.source.dto;

public record SourceCandidateDTO(String inputUrl, String origin, String officialDomain,
                                 String officialPageUrl, String employerName) { }
