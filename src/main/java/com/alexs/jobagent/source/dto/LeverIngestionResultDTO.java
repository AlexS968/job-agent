package com.alexs.jobagent.source.dto;

import com.alexs.jobagent.vacancy.dto.VacancyChangeDTO;

public record LeverIngestionResultDTO(long runId, VacancyChangeDTO change) {
}
