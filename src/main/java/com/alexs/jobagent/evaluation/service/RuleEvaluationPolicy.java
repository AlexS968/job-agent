package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.SearchProfileDTO;
import com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO;
import com.alexs.jobagent.source.dto.SourceEvidenceDTO;
import com.alexs.jobagent.vacancy.dto.VacancyReadDTO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RuleEvaluationPolicy {
    public static final String VERSION = "rules-v1";
    private final JsonMapper json;

    public RuleEvaluationPolicy(JsonMapper json) {
        this.json = json;
    }

    public VacancyEvaluationDTO evaluate(VacancyReadDTO vacancy, SourceEvidenceDTO source,
                                          LoadedProfileDTO loaded, String revision) {
        SearchProfileDTO profile = loaded.profile();
        List<String> reasons = new ArrayList<>();
        List<String> unknowns = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        evidence.add("title: " + excerpt(vacancy.title()));
        if (vacancy.location() != null) {
            evidence.add("location: " + excerpt(vacancy.location()));
        }
        JsonNode raw = json.readTree(source.rawPayload());
        JsonNode asserted = vacancy.sourceNamespace().startsWith("synthetic-") && raw != null
                ? raw.get("evaluationEvidence") : null;
        String title = vacancy.title().toLowerCase(Locale.ROOT);
        boolean excludedTitle = matching(profile.excludedTitleTerms(), title) != null;
        String role = matching(profile.preferredRoleTerms(), title);
        boolean reserve = false;
        if (role == null) {
            role = matching(profile.reserveRoleTerms(), title);
            reserve = role != null;
        }
        if (excludedTitle) {
            reasons.add("EXPLICIT_TITLE_EXCLUSION");
        } else if (role == null) {
            unknowns.add("ROLE_NOT_ESTABLISHED");
        } else {
            reasons.add(reserve ? "RESERVE_ROLE" : "PREFERRED_ROLE");
            evidence.add("matched title term: " + excerpt(role));
        }

        String arrangement = normalizeArrangement(field(asserted, "arrangement"));
        if (arrangement == null && raw != null) {
            String sourceLabel = field(raw, "workplaceType");
            arrangement = normalizeArrangement(sourceLabel);
            if (sourceLabel != null) {
                evidence.add("source workplace label: " + excerpt(sourceLabel));
            }
        }
        int rank = 9;
        boolean explicitCountryExclusion = false;
        if (asserted != null && asserted.isObject()) {
            String scope = field(asserted, "remoteScope");
            String hybridLocation = field(asserted, "hybridLocation");
            explicitCountryExclusion = contains(asserted.get("excludedCountries"), profile.workCountry());
            boolean countryAllowed = contains(asserted.get("eligibleCountries"), profile.workCountry());
            if (explicitCountryExclusion && countryAllowed) {
                unknowns.add("CONFLICTING_COUNTRY_ELIGIBILITY");
            } else if (explicitCountryExclusion) {
                reasons.add("EXPLICIT_COUNTRY_EXCLUSION");
            } else if ("REMOTE".equals(arrangement) && countryAllowed && "COUNTRY".equals(scope)) {
                rank = 1;
                reasons.add("CONFIRMED_COUNTRY_REMOTE");
                evidence.add("synthetic eligibility: country remote");
            } else if ("REMOTE".equals(arrangement) && countryAllowed && "EU".equals(scope)
                    && profile.allowEuRemote()) {
                rank = 2;
                reasons.add("CONFIRMED_EU_REMOTE");
                evidence.add("synthetic eligibility: EU remote with work-country permission");
            } else if ("HYBRID".equals(arrangement) && hybridLocation != null
                    && profile.allowedHybridLocations().stream().anyMatch(hybridLocation::equalsIgnoreCase)) {
                rank = 3;
                reasons.add("ACCEPTABLE_HYBRID_LOCATION");
                evidence.add("synthetic hybrid location: " + excerpt(hybridLocation));
            } else {
                unknowns.add("WORK_ELIGIBILITY_UNCONFIRMED");
            }
            salary(asserted.get("salary"), profile.salaryPreference(), reasons, unknowns);
        } else {
            unknowns.add("WORK_ELIGIBILITY_UNCONFIRMED");
            unknowns.add("SALARY_UNKNOWN");
        }
        boolean arrangementExcluded = arrangement != null
                && profile.excludedArrangements().contains(arrangement);
        if (arrangementExcluded) {
            reasons.add("EXPLICIT_ARRANGEMENT_EXCLUSION");
        }
        String decision = excludedTitle || arrangementExcluded || explicitCountryExclusion && !unknowns.contains(
                "CONFLICTING_COUNTRY_ELIGIBILITY") ? "EXCLUDED"
                : role != null && rank < 9 && !unknowns.contains("CONFLICTING_COUNTRY_ELIGIBILITY")
                ? "SUITABLE" : "NEEDS_REVIEW";
        String roleClassification = role == null ? "UNMATCHED" : reserve ? "RESERVE" : "PREFERRED";
        return new VacancyEvaluationDTO(vacancy.id(), source.sourceItemId(), revision, decision, rank,
                roleClassification, List.copyOf(reasons),
                List.copyOf(unknowns), List.copyOf(evidence), source.originalUrl());
    }

    private static String normalizeArrangement(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "remote" -> "REMOTE";
            case "hybrid" -> "HYBRID";
            case "onsite", "on-site" -> "ONSITE";
            default -> null;
        };
    }

    public String revision(VacancyReadDTO vacancy, SourceEvidenceDTO source) {
        return ProfileFileService.sha256(json.writeValueAsBytes(Arrays.asList(source.sourceItemId(), vacancy.title(), vacancy.location(),
                vacancy.description(), vacancy.contentDigest() == null ? source.rawPayload()
                        : vacancy.contentDigest(), source.originalUrl())));
    }

    private static String matching(List<String> terms, String title) {
        for (String term : terms) {
            String needle = term.toLowerCase(Locale.ROOT);
            int start = title.indexOf(needle);
            while (start >= 0) {
                int end = start + needle.length();
                if ((start == 0 || !Character.isLetterOrDigit(title.charAt(start - 1)))
                        && (end == title.length() || !Character.isLetterOrDigit(title.charAt(end)))) {
                    return term;
                }
                start = title.indexOf(needle, start + 1);
            }
        }
        return null;
    }

    private static boolean contains(JsonNode node, String text) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode value : node) {
            if (value.isTextual() && text.equalsIgnoreCase(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String field(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static void salary(JsonNode node, SearchProfileDTO.SalaryPreferenceDTO preference,
                               List<String> reasons, List<String> unknowns) {
        if (node == null || !node.isObject() || preference == null) {
            unknowns.add("SALARY_UNKNOWN");
            return;
        }
        String currency = field(node, "currency");
        String period = field(node, "period");
        JsonNode amount = node.get("amount");
        if (amount == null || !amount.canConvertToLong() || currency == null || period == null
                || !currency.equals(preference.currency()) || !period.equals(preference.period())) {
            unknowns.add("SALARY_NOT_COMPARABLE");
        } else if (amount.longValue() < preference.amount()) {
            reasons.add("SALARY_BELOW_SOFT_PREFERENCE");
        } else {
            reasons.add("SALARY_MEETS_SOFT_PREFERENCE");
        }
    }

    private static String excerpt(String value) {
        String safe = value.replaceAll("[\\p{Cntrl}]", " ");
        return safe.substring(0, Math.min(100, safe.length()));
    }
}
