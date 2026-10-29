package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.ExtractionInputDTO;
import com.alexs.jobagent.source.dto.SourceEvidenceDTO;
import com.alexs.jobagent.source.service.SourceEvidenceService;
import com.alexs.jobagent.vacancy.dto.VacancyReadDTO;
import com.alexs.jobagent.vacancy.service.VacancyReadService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ExtractionInputService {
    private final VacancyReadService vacancies;
    private final SourceEvidenceService sources;
    private final RuleEvaluationPolicy revisions;
    private final JsonMapper json;

    public ExtractionInputService(VacancyReadService vacancies, SourceEvidenceService sources,
                                  RuleEvaluationPolicy revisions, JsonMapper json) {
        this.vacancies = vacancies;
        this.sources = sources;
        this.revisions = revisions;
        this.json = json;
    }

    public ExtractionInputDTO load(long vacancyId) {
        return build(vacancies.read(vacancyId), vacancyId);
    }

    public ExtractionInputDTO loadLocked(long vacancyId) {
        return build(vacancies.lock(vacancyId), vacancyId);
    }

    private ExtractionInputDTO build(VacancyReadDTO vacancy, long vacancyId) {
        SourceEvidenceDTO source = sources.latestForVacancy(vacancyId);
        if (vacancy == null || source == null) {
            throw new IllegalArgumentException("Vacancy or source snapshot is unavailable");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "title", vacancy.title());
        put(fields, "location", vacancy.location());
        put(fields, "description", vacancy.description());
        JsonNode raw = json.readTree(source.rawPayload());
        if (raw != null && raw.isObject()) {
            put(fields, "workplaceType", raw.path("workplaceType").isString()
                    ? raw.path("workplaceType").asText() : null);
            JsonNode salary = raw.get("salary");
            if (salary != null && !salary.isNull()) {
                put(fields, "salary", salary.isString() ? salary.asText() : json.writeValueAsString(salary));
            }
        }
        return new ExtractionInputDTO(vacancyId, source.sourceItemId(), revisions.revision(vacancy, source),
                Map.copyOf(fields));
    }

    private static void put(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value);
        }
    }
}
