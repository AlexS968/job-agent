package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.SelectedExtractionDTO;
import com.alexs.jobagent.evaluation.repository.ExtractionRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ExtractionEvidenceService {
    private final ExtractionRepository repository;
    private final JsonMapper json;

    public ExtractionEvidenceService(ExtractionRepository repository, JsonMapper json) {
        this.repository = repository;
        this.json = json;
    }

    public SelectedExtractionDTO current(long vacancyId, long sourceItemId, String revision) {
        var row = repository.selectedEvidence(vacancyId, sourceItemId, revision,
                ExtractionPolicy.PRIMARY_MODEL, ExtractionPolicy.PROMPT_VERSION,
                ExtractionPolicy.SCHEMA_VERSION, ExtractionPolicy.POLICY_VERSION);
        if (row == null) {
            return null;
        }
        var output = json.readValue(row.resultJson(), com.alexs.jobagent.evaluation.dto.ExtractionOutputDTO.class);
        if (output.claims() == null || output.claims().isEmpty()) {
            return null;
        }
        var type = json.getTypeFactory().constructCollectionType(java.util.List.class,
                com.alexs.jobagent.evaluation.dto.ExtractionRejectionDTO.class);
        var rejections = json.<java.util.List<com.alexs.jobagent.evaluation.dto.ExtractionRejectionDTO>>readValue(
                row.rejectionsJson(), type);
        return new SelectedExtractionDTO(row.id(), row.status(), ExtractionPolicy.PRIMARY_MODEL,
                ExtractionPolicy.PROMPT_VERSION, ExtractionPolicy.SCHEMA_VERSION,
                ExtractionPolicy.POLICY_VERSION, row.rejectedCount(), rejections,
                java.util.List.copyOf(output.claims()));
    }
}
