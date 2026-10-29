package com.alexs.jobagent.evaluation.dto;

public record ExtractionResultDTO(long workId, String status, String model, long sourceItemId,
                                  String revision, ExtractionOutputDTO output, boolean reused) { }
