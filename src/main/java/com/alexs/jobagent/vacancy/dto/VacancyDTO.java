package com.alexs.jobagent.vacancy.dto;

public record VacancyDTO(String sourceNamespace, String listingId, String title, String location,
                         EmployerDTO employer) {
}
