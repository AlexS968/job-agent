package com.alexs.jobagent.vacancy.dto;

public record VacancyReadDTO(long id, String sourceNamespace, String title, String location,
                             String description, String contentDigest) { }
