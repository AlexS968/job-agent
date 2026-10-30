package com.alexs.jobagent.evaluation.dto;

import java.util.List;

public record SelectedExtractionDTO(long workId, String status, String model, String promptVersion,
                                    String schemaVersion, String policyVersion, int rejectedCount,
                                    List<ExtractionRejectionDTO> rejections,
                                    List<ExtractionClaimDTO> claims) { }
