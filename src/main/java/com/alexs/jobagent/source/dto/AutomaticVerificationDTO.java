package com.alexs.jobagent.source.dto;

public record AutomaticVerificationDTO(SourceVerificationDTO verification, String leadOutcome,
                                       boolean activated) { }
