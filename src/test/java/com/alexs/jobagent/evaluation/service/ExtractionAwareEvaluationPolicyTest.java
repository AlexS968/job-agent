package com.alexs.jobagent.evaluation.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.evaluation.dto.ExtractionClaimDTO;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.SearchProfileDTO;
import com.alexs.jobagent.evaluation.dto.SelectedExtractionDTO;
import com.alexs.jobagent.evaluation.dto.SkillEvidenceDTO;
import com.alexs.jobagent.source.dto.SourceEvidenceDTO;
import com.alexs.jobagent.vacancy.dto.VacancyReadDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ExtractionAwareEvaluationPolicyTest {
    private final ExtractionAwareEvaluationPolicy policy =
            new ExtractionAwareEvaluationPolicy(new RuleEvaluationPolicy(new JsonMapper()));
    private final LoadedProfileDTO profile = new LoadedProfileDTO(new SearchProfileDTO("fictional", "v1", "DE",
            List.of("Backend Engineer"), List.of(), List.of(), List.of("ONSITE"), List.of(), true,
            new SearchProfileDTO.SalaryPreferenceDTO(60000, "EUR", "YEAR")), "a".repeat(64));
    private final VacancyReadDTO vacancy = new VacancyReadDTO(1, "lever:global:example",
            "Backend Engineer", "Remote", "Java or Go. EUR 50000 yearly. Work in DE.", null);
    private final SourceEvidenceDTO source = new SourceEvidenceDTO(2, "https://jobs.example/1",
            "{\"workplaceType\":\"remote\"}", "v1", "v1");

    @Test
    void acceptedClaimsRemainEmployerStatementsAndSoftSalaryNeverExcludes() {
        var result = evaluate(List.of(
                claim("SKILL", "Java or Go", "Java or Go", "STATED",
                        new SkillEvidenceDTO("TECHNOLOGY", "UNKNOWN", "", "")),
                claim("COUNTRY_PERMISSION", "DE", "Work in DE", "STATED", null),
                claim("SALARY_MIN", "50000", "EUR 50000 yearly", "STATED", null),
                claim("SALARY_CURRENCY", "EUR", "EUR 50000 yearly", "STATED", null),
                claim("SALARY_PERIOD", "YEAR", "EUR 50000 yearly", "STATED", null)));
        assertEquals("NEEDS_REVIEW", result.decision());
        assertTrue(result.unknowns().contains("COUNTRY_PERMISSION_NOT_INDEPENDENTLY_VERIFIED"));
        assertTrue(result.reasons().contains("EXTRACTED_SALARY_BELOW_SOFT_PREFERENCE"));
        assertTrue(result.evidence().stream().anyMatch(s -> s.contains("Java or Go [UNKNOWN]")));
        assertFalse(result.evidence().stream().anyMatch(s -> s.contains("[REQUIRED]")));
    }

    @Test
    void conflictingAndUnsupportedSalaryCannotBecomeComparable() {
        var conflicting = evaluate(List.of(claim("SALARY_MIN", "50000", "EUR 50000", "CONFLICTING", null),
                claim("SALARY_CURRENCY", "EUR", "EUR", "STATED", null),
                claim("SALARY_PERIOD", "YEAR", "year", "STATED", null)));
        assertTrue(conflicting.unknowns().contains("EXTRACTED_SALARY_CONFLICT_OR_UNCERTAINTY"));
        assertFalse(conflicting.reasons().contains("EXTRACTED_SALARY_BELOW_SOFT_PREFERENCE"));
        var unsupported = evaluate(List.of(claim("SALARY_MIN", "50000", "USD 50000 monthly", "STATED", null),
                claim("SALARY_CURRENCY", "USD", "USD", "STATED", null),
                claim("SALARY_PERIOD", "MONTH", "monthly", "STATED", null)));
        assertTrue(unsupported.unknowns().contains("EXTRACTED_SALARY_NOT_COMPARABLE"));
    }

    @Test
    void extractedArrangementDoesNotOverrideSourceExclusion() {
        var onsite = new SourceEvidenceDTO(2, "https://jobs.example/1",
                "{\"workplaceType\":\"on-site\"}", "v1", "v1");
        var result = policy.evaluate(vacancy, onsite, profile, "b".repeat(64),
                selected(List.of(claim("ARRANGEMENT", "REMOTE", "remote", "STATED", null))));
        assertEquals("EXCLUDED", result.decision());
        assertTrue(result.reasons().contains("EXPLICIT_ARRANGEMENT_EXCLUSION"));
        assertTrue(result.evidence().stream().anyMatch(s -> s.contains("extracted ARRANGEMENT")));
    }

    @Test
    void sourceAndExtractionSalaryDisagreementStaysUnknown() {
        var statedSalary = new SourceEvidenceDTO(2, "https://jobs.example/1", """
                {"evaluationEvidence":{"salary":{"amount":90000,"currency":"EUR","period":"YEAR"}}}
                """, "v1", "v1");
        var result = policy.evaluate(new VacancyReadDTO(1, "synthetic-board", "Backend Engineer",
                "Remote", "EUR 50000 yearly", null), statedSalary, profile, "b".repeat(64),
                selected(List.of(claim("SALARY_MIN", "50000", "EUR 50000 yearly", "STATED", null),
                        claim("SALARY_CURRENCY", "EUR", "EUR 50000 yearly", "STATED", null),
                        claim("SALARY_PERIOD", "YEAR", "EUR 50000 yearly", "STATED", null))));
        assertTrue(result.unknowns().contains("SOURCE_EXTRACTION_SALARY_DISAGREEMENT"));
        assertFalse(result.reasons().contains("SALARY_MEETS_SOFT_PREFERENCE"));
        assertFalse(result.reasons().contains("EXTRACTED_SALARY_BELOW_SOFT_PREFERENCE"));
    }

    @Test
    void uncertainSkillCannotBePresentedAsMandatory() {
        var result = evaluate(List.of(claim("SKILL", "Java", "Java required", "UNCERTAIN",
                new SkillEvidenceDTO("TECHNOLOGY", "REQUIRED", "", "Java required"))));
        assertTrue(result.evidence().stream().anyMatch(s -> s.contains("skill Java [UNKNOWN]")));
        assertFalse(result.evidence().stream().anyMatch(s -> s.contains("[REQUIRED]")));
        assertTrue(result.unknowns().contains("EXTRACTION_SKILL_UNCERTAIN"));
    }

    private com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO evaluate(List<ExtractionClaimDTO> claims) {
        return policy.evaluate(vacancy, source, profile, "b".repeat(64), selected(claims));
    }

    private static SelectedExtractionDTO selected(List<ExtractionClaimDTO> claims) {
        return new SelectedExtractionDTO(3, "SUCCEEDED", ExtractionPolicy.PRIMARY_MODEL,
                ExtractionPolicy.PROMPT_VERSION, ExtractionPolicy.SCHEMA_VERSION,
                ExtractionPolicy.POLICY_VERSION, 0, List.of(), claims);
    }

    private static ExtractionClaimDTO claim(String fact, String value, String quote,
                                            String uncertainty, SkillEvidenceDTO skill) {
        return new ExtractionClaimDTO(fact, value, "description", quote, uncertainty, skill);
    }
}
