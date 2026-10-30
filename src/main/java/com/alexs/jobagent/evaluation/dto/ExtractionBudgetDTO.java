package com.alexs.jobagent.evaluation.dto;

public record ExtractionBudgetDTO(long reservedMicroUsd, long observedMicroUsd, int attempts,
                                  int primaryDistinct, int comparisonDistinct, Long activeWorkId,
                                  String haltedReason, int claimed, int succeeded, int partial, int failed,
                                  int uncertain, int expiredClaims) { }
