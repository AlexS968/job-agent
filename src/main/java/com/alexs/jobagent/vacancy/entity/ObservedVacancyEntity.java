package com.alexs.jobagent.vacancy.entity;

import java.time.Instant;

public record ObservedVacancyEntity(long id, long employerDbId, Instant observationStartedAt,
                                    String contentDigest) {
}
