package com.alexs.jobagent.evaluation.dto;

import java.util.UUID;

public record ExtractionCompletionDTO(long workId, UUID claimToken, String status, String resultJson,
                                      String outcome, ExtractionUsageDTO usage, long reservedMicroUsd) { }
