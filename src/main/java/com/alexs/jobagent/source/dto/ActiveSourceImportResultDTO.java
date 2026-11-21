package com.alexs.jobagent.source.dto;

public record ActiveSourceImportResultDTO(int sources, int failures, int created, int updated,
                                          int unchanged, int unavailableSources) {
    public ActiveSourceImportResultDTO(int sources, int failures, int created, int updated, int unchanged) {
        this(sources, failures, created, updated, unchanged, 0);
    }

    public int blockingFailures() {
        return failures - unavailableSources;
    }
}
