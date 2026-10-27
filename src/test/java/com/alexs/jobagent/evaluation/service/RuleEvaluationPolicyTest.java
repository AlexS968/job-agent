package com.alexs.jobagent.evaluation.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.SearchProfileDTO;
import com.alexs.jobagent.source.dto.SourceEvidenceDTO;
import com.alexs.jobagent.vacancy.dto.VacancyReadDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RuleEvaluationPolicyTest {
    private final RuleEvaluationPolicy policy = new RuleEvaluationPolicy(new JsonMapper());
    private final LoadedProfileDTO profile = new LoadedProfileDTO(new SearchProfileDTO("fictional", "v1", "DE",
            List.of("Senior Java", "Backend Engineer"), List.of("Application Manager"),
            List.of("Intern"), List.of("ONSITE"), List.of("Berlin"), true,
            new SearchProfileDTO.SalaryPreferenceDTO(60000, "EUR", "YEAR")), "a".repeat(64));

    @Test
    void preferredCountryRemoteIsSuitableEvenWithMissingOrLowSalary() {
        var missing = evaluate("Senior Java Engineer", "{\"evaluationEvidence\":{\"arrangement\":\"REMOTE\","
                + "\"remoteScope\":\"COUNTRY\",\"eligibleCountries\":[\"DE\"]}}");
        assertEquals("SUITABLE", missing.decision());
        assertEquals(1, missing.preferenceRank());
        assertTrue(missing.reasons().contains("PREFERRED_ROLE"));
        assertTrue(missing.unknowns().contains("SALARY_UNKNOWN"));
        assertTrue(missing.evidence().stream().anyMatch(value -> value.startsWith("title:")));
        assertEquals("https://jobs.example/1", missing.originalUrl());

        var low = evaluate("Senior Java Engineer", "{\"evaluationEvidence\":{\"arrangement\":\"REMOTE\","
                + "\"remoteScope\":\"COUNTRY\",\"eligibleCountries\":[\"DE\"],"
                + "\"salary\":{\"amount\":45000,\"currency\":\"EUR\",\"period\":\"YEAR\"}}}");
        assertEquals("SUITABLE", low.decision());
        assertTrue(low.reasons().contains("SALARY_BELOW_SOFT_PREFERENCE"));
    }

    @Test
    void euRemoteAndAcceptableHybridAreRankedAfterCountryRemote() {
        var eu = evaluate("Backend Engineer", "{\"evaluationEvidence\":{\"arrangement\":\"REMOTE\","
                + "\"remoteScope\":\"EU\",\"eligibleCountries\":[\"DE\"]}}");
        var hybrid = evaluate("Application Manager", "{\"evaluationEvidence\":{\"arrangement\":\"HYBRID\","
                + "\"hybridLocation\":\"Berlin\"}}");
        assertEquals("SUITABLE", eu.decision());
        assertEquals(2, eu.preferenceRank());
        assertEquals("SUITABLE", hybrid.decision());
        assertEquals(3, hybrid.preferenceRank());
        assertTrue(hybrid.reasons().contains("RESERVE_ROLE"));
    }

    @Test
    void genericRemoteLabelAndUnknownRoleNeverProveEligibilityOrSkillFit() {
        var live = policy.evaluate(new VacancyReadDTO(1, "lever:global:example", "Backend Engineer", "Remote",
                        "Germany mentioned here", "b".repeat(64)),
                new SourceEvidenceDTO(1, "https://jobs.example/1", "{\"workplaceType\":\"remote\"}", "v1", "v1"),
                profile, "c".repeat(64));
        assertEquals("NEEDS_REVIEW", live.decision());
        assertTrue(live.unknowns().contains("WORK_ELIGIBILITY_UNCONFIRMED"));
        assertFalse(live.reasons().contains("CONFIRMED_COUNTRY_REMOTE"));
        var role = evaluate("Office Coordinator", "{\"evaluationEvidence\":{\"arrangement\":\"REMOTE\","
                + "\"remoteScope\":\"COUNTRY\",\"eligibleCountries\":[\"DE\"]}}");
        assertEquals("NEEDS_REVIEW", role.decision());
        assertTrue(role.unknowns().contains("ROLE_NOT_ESTABLISHED"));
    }

    @Test
    void leverOnSiteLabelIsExcludedButUnknownLabelRemainsForReview() {
        var lever = new VacancyReadDTO(1, "lever:global:example", "Backend Engineer", "Berlin", null,
                "b".repeat(64));
        var onSite = policy.evaluate(lever, new SourceEvidenceDTO(1, "https://jobs.example/1", """
                {"id":"listing-1","text":"Backend Engineer","categories":{"location":"Berlin"},
                 "workplaceType":"on-site"}
                """, "lever-postings-v1", "lever-normalization-v1"), profile, "c".repeat(64));
        assertEquals("EXCLUDED", onSite.decision());
        assertTrue(onSite.reasons().contains("EXPLICIT_ARRANGEMENT_EXCLUSION"));
        assertTrue(onSite.evidence().contains("source workplace label: on-site"));

        var unknown = policy.evaluate(lever, new SourceEvidenceDTO(2, "https://jobs.example/1", """
                {"id":"listing-1","workplaceType":"field-based"}
                """, "lever-postings-v1", "lever-normalization-v1"), profile, "c".repeat(64));
        assertEquals("NEEDS_REVIEW", unknown.decision());
        assertFalse(unknown.reasons().contains("EXPLICIT_ARRANGEMENT_EXCLUSION"));
        assertTrue(unknown.unknowns().contains("WORK_ELIGIBILITY_UNCONFIRMED"));
        assertTrue(unknown.evidence().contains("source workplace label: field-based"));
    }

    @Test
    void explicitExclusionsAndContradictionsAreConservative() {
        assertEquals("EXCLUDED", evaluate("Senior Java Intern", "{}").decision());
        assertEquals("EXCLUDED", evaluate("Backend Engineer", "{\"evaluationEvidence\":{\"arrangement\":\"ONSITE\"}}")
                .decision());
        var denied = evaluate("Backend Engineer", "{\"evaluationEvidence\":{\"arrangement\":\"REMOTE\","
                + "\"remoteScope\":\"COUNTRY\",\"excludedCountries\":[\"DE\"]}}");
        assertEquals("EXCLUDED", denied.decision());
        var conflict = evaluate("Backend Engineer", "{\"evaluationEvidence\":{\"arrangement\":\"REMOTE\","
                + "\"remoteScope\":\"COUNTRY\",\"eligibleCountries\":[\"DE\"],"
                + "\"excludedCountries\":[\"DE\"]}}");
        assertEquals("NEEDS_REVIEW", conflict.decision());
        assertTrue(conflict.unknowns().contains("CONFLICTING_COUNTRY_ELIGIBILITY"));
    }

    @Test
    void unsupportedSalaryComparisonStaysUnknown() {
        var result = evaluate("Backend Engineer", "{\"evaluationEvidence\":{\"arrangement\":\"REMOTE\","
                + "\"remoteScope\":\"COUNTRY\",\"eligibleCountries\":[\"DE\"],"
                + "\"salary\":{\"amount\":90000,\"currency\":\"USD\",\"period\":\"MONTH\"}}}");
        assertEquals("SUITABLE", result.decision());
        assertTrue(result.unknowns().contains("SALARY_NOT_COMPARABLE"));
    }

    private com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO evaluate(String title, String raw) {
        return policy.evaluate(new VacancyReadDTO(1, "synthetic-board", title, "Remote", null, null),
                new SourceEvidenceDTO(1, "https://jobs.example/1", raw, "v1", "v1"), profile, "c".repeat(64));
    }
}
