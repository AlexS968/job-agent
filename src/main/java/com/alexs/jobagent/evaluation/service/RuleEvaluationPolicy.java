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
    private static final String REMOTE = "REMOTE";
    private static final String UNMATCHED = "UNMATCHED";
    private static final String CONFLICTING_COUNTRY_ELIGIBILITY = "CONFLICTING_COUNTRY_ELIGIBILITY";
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
        RoleAssessment role = assessRole(profile, vacancy.title(), reasons, unknowns, evidence);
        String arrangement = arrangement(asserted, raw, evidence);
        WorkAssessment work = assessWork(profile, asserted, arrangement, reasons, unknowns, evidence);
        salary(asserted == null ? null : asserted.get("salary"), profile.salaryPreference(), reasons, unknowns);
        boolean arrangementExcluded = arrangement != null
                && profile.excludedArrangements().contains(arrangement);
        if (arrangementExcluded) {
            reasons.add("EXPLICIT_ARRANGEMENT_EXCLUSION");
        }
        String decision = decision(role, work, arrangementExcluded);
        return new VacancyEvaluationDTO(vacancy.id(), source.sourceItemId(), revision, decision, work.rank(),
                role.classification(), List.copyOf(reasons),
                List.copyOf(unknowns), List.copyOf(evidence), source.originalUrl());
    }

    private static RoleAssessment assessRole(SearchProfileDTO profile, String title, List<String> reasons,
                                             List<String> unknowns, List<String> evidence) {
        String lowered = title.toLowerCase(Locale.ROOT);
        boolean excluded = matching(profile.excludedTitleTerms(), lowered) != null;
        String preferred = matching(profile.preferredRoleTerms(), lowered);
        String reserve = preferred == null ? matching(profile.reserveRoleTerms(), lowered) : null;
        String classification;
        if (preferred != null) {
            classification = "PREFERRED";
        } else if (reserve != null) {
            classification = "RESERVE";
        } else {
            classification = UNMATCHED;
        }
        if (excluded) {
            reasons.add("EXPLICIT_TITLE_EXCLUSION");
        } else if (UNMATCHED.equals(classification)) {
            unknowns.add("ROLE_NOT_ESTABLISHED");
        } else {
            reasons.add(classification + "_ROLE");
            evidence.add("matched title term: " + excerpt(preferred != null ? preferred : reserve));
        }
        return new RoleAssessment(classification, excluded);
    }

    private static String arrangement(JsonNode asserted, JsonNode raw, List<String> evidence) {
        String normalized = normalizeArrangement(field(asserted, "arrangement"));
        if (normalized != null || raw == null) {
            return normalized;
        }
        String label = field(raw, "workplaceType");
        if (label != null) {
            evidence.add("source workplace label: " + excerpt(label));
        }
        return normalizeArrangement(label);
    }

    private static WorkAssessment assessWork(SearchProfileDTO profile, JsonNode asserted, String arrangement,
                                             List<String> reasons, List<String> unknowns, List<String> evidence) {
        if (asserted == null || !asserted.isObject()) {
            unknowns.add("WORK_ELIGIBILITY_UNCONFIRMED");
            return new WorkAssessment(9, false, false);
        }
        boolean excluded = contains(asserted.get("excludedCountries"), profile.workCountry());
        boolean allowed = contains(asserted.get("eligibleCountries"), profile.workCountry());
        if (excluded && allowed) {
            unknowns.add(CONFLICTING_COUNTRY_ELIGIBILITY);
            return new WorkAssessment(9, true, true);
        }
        if (excluded) {
            reasons.add("EXPLICIT_COUNTRY_EXCLUSION");
            return new WorkAssessment(9, true, false);
        }
        return new WorkAssessment(confirmedWorkRank(profile, asserted, arrangement, allowed,
                reasons, unknowns, evidence), false, false);
    }

    private static int confirmedWorkRank(SearchProfileDTO profile, JsonNode asserted, String arrangement,
                                         boolean countryAllowed, List<String> reasons, List<String> unknowns,
                                         List<String> evidence) {
        String scope = field(asserted, "remoteScope");
        String hybridLocation = field(asserted, "hybridLocation");
        if (REMOTE.equals(arrangement) && countryAllowed && "COUNTRY".equals(scope)) {
            reasons.add("CONFIRMED_COUNTRY_REMOTE");
            evidence.add("synthetic eligibility: country remote");
            return 1;
        }
        if (REMOTE.equals(arrangement) && countryAllowed && "EU".equals(scope) && profile.allowEuRemote()) {
            reasons.add("CONFIRMED_EU_REMOTE");
            evidence.add("synthetic eligibility: EU remote with work-country permission");
            return 2;
        }
        if ("HYBRID".equals(arrangement) && hybridLocation != null
                && profile.allowedHybridLocations().stream().anyMatch(hybridLocation::equalsIgnoreCase)) {
            reasons.add("ACCEPTABLE_HYBRID_LOCATION");
            evidence.add("synthetic hybrid location: " + excerpt(hybridLocation));
            return 3;
        }
        unknowns.add("WORK_ELIGIBILITY_UNCONFIRMED");
        return 9;
    }

    private static String decision(RoleAssessment role, WorkAssessment work, boolean arrangementExcluded) {
        if (role.excluded() || arrangementExcluded || work.countryExcluded() && !work.conflicting()) {
            return "EXCLUDED";
        }
        if (!UNMATCHED.equals(role.classification()) && work.rank() < 9 && !work.conflicting()) {
            return "SUITABLE";
        }
        return "NEEDS_REVIEW";
    }

    private record RoleAssessment(String classification, boolean excluded) { }
    private record WorkAssessment(int rank, boolean countryExcluded, boolean conflicting) { }

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
