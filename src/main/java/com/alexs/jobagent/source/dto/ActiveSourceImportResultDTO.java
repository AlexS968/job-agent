package com.alexs.jobagent.source.dto;

public record ActiveSourceImportResultDTO(int sources, int failures, int created, int updated, int unchanged) {
}
