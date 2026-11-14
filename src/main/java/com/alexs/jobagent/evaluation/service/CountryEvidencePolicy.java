package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.source.dto.SourceEvidenceDTO;
import com.alexs.jobagent.vacancy.dto.VacancyReadDTO;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A location is evidence of a work country only when it is a current source field. */
@Component
public class CountryEvidencePolicy {
    public static final String VERSION = "country-v1";
    private final JsonMapper json;

    public CountryEvidencePolicy(JsonMapper json) {
        this.json = json;
    }

    public CountryEvidence assess(VacancyReadDTO vacancy, SourceEvidenceDTO source, String country) {
        if (!"DE".equals(country) && !"AT".equals(country)) {
            return unknown();
        }
        String namespace = vacancy.sourceNamespace();
        if (namespace == null || source.rawPayload() == null) {
            return unknown();
        }
        JsonNode raw;
        try {
            raw = json.readTree(source.rawPayload());
        } catch (RuntimeException malformed) {
            return unknown();
        }
        if (raw == null || !raw.isObject()) {
            return unknown();
        }
        String location;
        if (namespace.startsWith("greenhouse:") && "greenhouse-board-v1".equals(source.inputVersion())) {
            JsonNode field = raw.path("location").path("name");
            location = field.isTextual() ? field.asText() : null;
        } else if (namespace.startsWith("lever:") && "lever-postings-v1".equals(source.inputVersion())) {
            JsonNode field = raw.path("categories").path("location");
            location = field.isTextual() ? field.asText() : null;
        } else {
            return unknown();
        }
        if (location == null || !location.equals(vacancy.location()) || location.length() > 255) {
            return unknown();
        }
        String normalized = location.strip().toLowerCase(Locale.ROOT);
        // A country in a dedicated posting-location field is useful evidence. General
        // "remote", EU-wide labels and short work-abroad allowances are not.
        boolean german = normalized.matches(".*\\b(germany|deutschland|berlin)\\b.*");
        boolean austrian = normalized.matches(".*\\b(austria|österreich|vienna|wien)\\b.*");
        if (german == austrian || normalized.matches(".*\\b(not|except|temporary|weeks?)\\b.*")) {
            return unknown();
        }
        if (("DE".equals(country) && german) || ("AT".equals(country) && austrian)) {
            return new CountryEvidence("CONFIRMED", "source location: " + location);
        }
        return unknown();
    }

    private static CountryEvidence unknown() {
        return new CountryEvidence("UNKNOWN", null);
    }

    public record CountryEvidence(String status, String evidence) { }
}
