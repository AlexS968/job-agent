package com.alexs.jobagent.vacancy.entity;

import java.time.Instant;

public record LiveVacancyEntity(long id, long employerDbId, Instant sourceUpdatedAt, String contentDigest) {
}
