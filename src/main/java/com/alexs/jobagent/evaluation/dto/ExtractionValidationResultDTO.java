package com.alexs.jobagent.evaluation.dto;

import java.util.List;

public record ExtractionValidationResultDTO(ExtractionOutputDTO output,
                                            List<ExtractionRejectionDTO> rejections) {
    public int acceptedCount() {
        return output.claims().size();
    }

    public int rejectedCount() {
        return rejections.size();
    }
}
