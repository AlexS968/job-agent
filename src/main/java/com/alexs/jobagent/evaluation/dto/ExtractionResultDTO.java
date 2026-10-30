package com.alexs.jobagent.evaluation.dto;

import java.util.List;

public record ExtractionResultDTO(long workId, String status, String model, long sourceItemId,
                                  String revision, ExtractionOutputDTO output, boolean reused,
                                  int acceptedCount, int rejectedCount,
                                  List<ExtractionRejectionDTO> rejections) {
    public ExtractionResultDTO(long workId, String status, String model, long sourceItemId,
                               String revision, ExtractionOutputDTO output, boolean reused) {
        this(workId, status, model, sourceItemId, revision, output, reused,
                output == null ? 0 : output.claims().size(), 0, List.of());
    }
}
