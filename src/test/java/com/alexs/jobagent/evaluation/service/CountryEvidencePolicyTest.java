package com.alexs.jobagent.evaluation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alexs.jobagent.source.dto.SourceEvidenceDTO;
import com.alexs.jobagent.vacancy.dto.VacancyReadDTO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CountryEvidencePolicyTest {
    private final CountryEvidencePolicy policy = new CountryEvidencePolicy(new JsonMapper());

    @Test
    void berlinAndTemporaryAbroadDoNotEstablishAustria() {
        var source = lever("Berlin", "Employees may work from Austria for 12 weeks per year");
        assertEquals("CONFIRMED", assess(source, "Berlin", "DE"));
        assertEquals("UNKNOWN", assess(source, "Berlin", "AT"));
    }

    @Test
    void explicitCountryLocationSupportsLocalAndRemoteWork() {
        assertEquals("CONFIRMED", assess(lever("Vienna, Austria", ""), "Vienna, Austria", "AT"));
        assertEquals("UNKNOWN", assess(lever("Vienna, Austria", ""), "Vienna, Austria", "DE"));
        assertEquals("CONFIRMED", assess(lever("Remote - Austria", ""), "Remote - Austria", "AT"));
        assertEquals("CONFIRMED", assess(greenhouse("Remote, Germany"), "Remote, Germany", "DE"));
    }

    @Test
    void genericRemoteAmbiguousLocationAndMismatchedSnapshotRemainUnknown() {
        assertEquals("UNKNOWN", assess(lever("Remote", "Austria Germany"), "Remote", "AT"));
        assertEquals("UNKNOWN", assess(lever("Germany / Austria", ""), "Germany / Austria", "AT"));
        assertEquals("UNKNOWN", assess(lever("Remote - Austria", ""), "Remote - Germany", "AT"));
    }

    private String assess(SourceEvidenceDTO source, String location, String country) {
        String namespace = source.inputVersion().startsWith("greenhouse") ? "greenhouse:demo" : "lever:global:demo";
        return policy.assess(new VacancyReadDTO(1, namespace, "Backend Engineer", location,
                "", "a".repeat(64)), source, country).status();
    }

    private static SourceEvidenceDTO lever(String location, String description) {
        String raw = "{\"categories\":{\"location\":\"" + location + "\"},\"description\":\""
                + description + "\"}";
        return new SourceEvidenceDTO(1, "https://jobs.example/1", raw, "lever-postings-v1", "v1");
    }

    private static SourceEvidenceDTO greenhouse(String location) {
        return new SourceEvidenceDTO(1, "https://jobs.example/1",
                "{\"location\":{\"name\":\"" + location + "\"}}", "greenhouse-board-v1", "v1");
    }
}
