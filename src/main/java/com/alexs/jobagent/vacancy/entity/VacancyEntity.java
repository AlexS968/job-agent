package com.alexs.jobagent.vacancy.entity;

public record VacancyEntity(long id, long employerDbId, String sourceNamespace, String listingId,
                            String title, String location) {
}
