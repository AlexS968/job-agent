package com.alexs.jobagent.evaluation.dto;

import java.util.Map;

public record ExtractionInputDTO(long vacancyId, long sourceItemId, String revision,
                                 Map<String, String> sourceFields) { }
