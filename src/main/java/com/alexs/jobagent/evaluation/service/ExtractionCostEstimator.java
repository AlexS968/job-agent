package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.ExtractionInputDTO;

public interface ExtractionCostEstimator {
    long estimatedMicroUsd(ExtractionInputDTO input, String model);
}
