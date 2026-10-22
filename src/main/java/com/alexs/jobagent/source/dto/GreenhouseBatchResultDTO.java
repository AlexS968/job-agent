package com.alexs.jobagent.source.dto;

public record GreenhouseBatchResultDTO(int requests, int fetched, int created, int updated,
                                       int unchanged, int rejected, int failed) {
}
