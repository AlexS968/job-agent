package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.ExtractionClaimDTO;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.SelectedExtractionDTO;
import com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO;
import com.alexs.jobagent.source.dto.SourceEvidenceDTO;
import com.alexs.jobagent.vacancy.dto.VacancyReadDTO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ExtractionAwareEvaluationPolicy {
    public static final String VERSION = "rules-extraction-v1";
    private static final Set<String> SALARY_FACTS = Set.of("SALARY_MIN", "SALARY_MAX", "SALARY_CURRENCY", "SALARY_PERIOD");
    private final RuleEvaluationPolicy baseline;

    public ExtractionAwareEvaluationPolicy(RuleEvaluationPolicy baseline) {
        this.baseline = baseline;
    }

    public VacancyEvaluationDTO evaluate(VacancyReadDTO vacancy, SourceEvidenceDTO source,
                                          LoadedProfileDTO profile, String revision,
                                          SelectedExtractionDTO extraction) {
        VacancyEvaluationDTO base = baseline.evaluate(vacancy, source, profile, revision);
        List<String> reasons = new ArrayList<>(base.reasons());
        List<String> unknowns = new ArrayList<>(base.unknowns());
        List<String> evidence = new ArrayList<>(base.evidence());
        if (extraction == null) {
            unknowns.add("EXTRACTION_EVIDENCE_UNAVAILABLE");
            return base.withEvidence(reasons, unknowns, evidence, null, "NONE", 0);
        }
        if ("PARTIAL".equals(extraction.status())) {
            unknowns.add("PARTIAL_EXTRACTION_REJECTED_" + extraction.rejectedCount());
            extraction.rejections().stream().map(item -> item.category())
                    .filter(category -> category.matches("[A-Z_]{1,60}"))
                    .distinct().forEach(category -> unknowns.add("EXTRACTION_REJECTION_" + category));
        }
        for (ExtractionClaimDTO claim : extraction.claims()) {
            if (!"STATED".equals(claim.uncertainty())) {
                unknowns.add("EXTRACTION_" + claim.fact() + "_" + claim.uncertainty());
            }
            if ("SKILL".equals(claim.fact()) && claim.skillEvidence() != null) {
                String strength = "STATED".equals(claim.uncertainty())
                        ? claim.skillEvidence().requirementStrength() : "UNKNOWN";
                evidence.add("skill " + excerpt(claim.value()) + " [" + strength + "]: " + excerpt(claim.quote())
                        + section(claim));
            } else if ("TITLE".equals(claim.fact()) || "FAMILY".equals(claim.fact())
                    || "SENIORITY".equals(claim.fact()) || "ARRANGEMENT".equals(claim.fact())
                    || "COUNTRY_PERMISSION".equals(claim.fact()) || "COUNTRY_RESTRICTION".equals(claim.fact())) {
                evidence.add("extracted " + claim.fact() + " [" + claim.uncertainty() + "]: "
                        + excerpt(claim.value()) + " — " + excerpt(claim.quote()));
            }
        }
        // Provider country statements cannot establish independent work authorization.
        if (extraction.claims().stream().anyMatch(c -> "COUNTRY_PERMISSION".equals(c.fact()))) {
            unknowns.add("COUNTRY_PERMISSION_NOT_INDEPENDENTLY_VERIFIED");
        }
        addSalary(profile, extraction.claims(), reasons, unknowns, evidence);
        return base.withEvidence(reasons, unknowns, evidence, extraction.workId(), extraction.status(),
                extraction.rejectedCount());
    }

    private static String section(ExtractionClaimDTO claim) {
        String quote = claim.skillEvidence().sectionQuote();
        return quote == null || quote.isBlank() ? "" : " (section: " + excerpt(quote) + ")";
    }

    private static void addSalary(LoadedProfileDTO profile, List<ExtractionClaimDTO> claims,
                                  List<String> reasons, List<String> unknowns, List<String> evidence) {
        List<ExtractionClaimDTO> salary = claims.stream().filter(c -> SALARY_FACTS.contains(c.fact())).toList();
        for (ExtractionClaimDTO claim : salary) {
            evidence.add("extracted " + claim.fact() + " [" + claim.uncertainty() + "]: "
                    + excerpt(claim.value()) + " — " + excerpt(claim.quote()));
        }
        if (salary.isEmpty()) {
            return;
        }
        if (salary.stream().anyMatch(c -> !"STATED".equals(c.uncertainty()))) {
            unknowns.add("EXTRACTED_SALARY_CONFLICT_OR_UNCERTAINTY");
            return;
        }
        var preference = profile.profile().salaryPreference();
        if (preference == null) {
            return;
        }
        String currency = single(salary, "SALARY_CURRENCY");
        String period = single(salary, "SALARY_PERIOD");
        String amount = single(salary, "SALARY_MIN");
        if (amount == null || !preference.currency().equals(currency) || !preference.period().equals(period)) {
            unknowns.add("EXTRACTED_SALARY_NOT_COMPARABLE");
            return;
        }
        try {
            boolean below = new BigDecimal(amount).compareTo(BigDecimal.valueOf(preference.amount())) < 0;
            if (unknowns.contains("SALARY_NOT_COMPARABLE")
                    || below && reasons.contains("SALARY_MEETS_SOFT_PREFERENCE")
                    || !below && reasons.contains("SALARY_BELOW_SOFT_PREFERENCE")) {
                reasons.removeIf(reason -> reason.startsWith("SALARY_") && reason.endsWith("_SOFT_PREFERENCE"));
                unknowns.add("SOURCE_EXTRACTION_SALARY_DISAGREEMENT");
                return;
            }
            unknowns.remove("SALARY_UNKNOWN");
            reasons.add(below ? "EXTRACTED_SALARY_BELOW_SOFT_PREFERENCE"
                    : "EXTRACTED_SALARY_MEETS_SOFT_PREFERENCE");
        } catch (NumberFormatException invalid) {
            unknowns.add("EXTRACTED_SALARY_NOT_COMPARABLE");
        }
    }

    private static String single(List<ExtractionClaimDTO> claims, String fact) {
        List<String> values = claims.stream().filter(c -> fact.equals(c.fact())).map(ExtractionClaimDTO::value)
                .distinct().toList();
        return values.size() == 1 ? values.getFirst() : null;
    }

    private static String excerpt(String text) {
        String safe = text.replaceAll("[\\p{Cntrl}]", " ");
        return safe.substring(0, Math.min(safe.length(), 160));
    }
}
