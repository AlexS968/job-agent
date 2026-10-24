package com.alexs.jobagent.source.dto;

public record SearchQueryDTO(String query, String country, String language, int maxResults) {
    public SearchQueryDTO {
        if (query == null || query.isBlank() || query.length() > 400 || maxResults < 1 || maxResults > 10
                || language == null || !language.matches("[a-z]{2}")) {
            throw new IllegalArgumentException("Invalid search query");
        }
        if (country != null && !country.matches("[a-z]+")) {
            throw new IllegalArgumentException("Invalid search country");
        }
    }
}
