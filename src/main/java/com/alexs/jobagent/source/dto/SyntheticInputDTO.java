package com.alexs.jobagent.source.dto;

import com.alexs.jobagent.vacancy.dto.VacancyDTO;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

public record SyntheticInputDTO(String sourceNamespace, String sourceItemId, String originalUrl,
                                Instant fetchedAt, JsonNode rawPayload, String inputVersion,
                                String normalizationVersion, VacancyDTO vacancy) {
}
