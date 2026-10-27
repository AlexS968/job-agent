package com.alexs.jobagent.evaluation.dto;

import java.util.List;

public record SearchProfileDTO(String profileId, String version, String workCountry,
                               List<String> preferredRoleTerms, List<String> reserveRoleTerms,
                               List<String> excludedTitleTerms, List<String> excludedArrangements,
                               List<String> allowedHybridLocations, boolean allowEuRemote,
                               SalaryPreferenceDTO salaryPreference) {
    public record SalaryPreferenceDTO(long amount, String currency, String period) { }
}
