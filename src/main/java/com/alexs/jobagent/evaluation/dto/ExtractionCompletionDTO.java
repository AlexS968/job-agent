package com.alexs.jobagent.evaluation.dto;

import java.util.UUID;

public record ExtractionCompletionDTO(long workId, UUID claimToken, String status, String resultJson,
                                      String outcome, ExtractionUsageDTO usage, long reservedMicroUsd,
                                      int acceptedCount, int rejectedCount, String rejectionDiagnosticsJson) {
    public ExtractionCompletionDTO(long workId, UUID claimToken, String status, String resultJson,
                                   String outcome, ExtractionUsageDTO usage, long reservedMicroUsd) {
        this(workId, claimToken, status, resultJson, outcome, usage, reservedMicroUsd, 0, 0, "[]");
    }
}
