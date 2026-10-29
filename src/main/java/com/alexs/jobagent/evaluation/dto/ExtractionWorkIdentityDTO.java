package com.alexs.jobagent.evaluation.dto;

public record ExtractionWorkIdentityDTO(long vacancyId, long sourceItemId, String revision, String model,
                                        String promptVersion, String schemaVersion, String policyVersion) { }
