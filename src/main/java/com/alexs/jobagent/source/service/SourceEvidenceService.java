package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.SourceEvidenceDTO;
import com.alexs.jobagent.source.repository.SourceItemRepository;
import org.springframework.stereotype.Service;

@Service
public class SourceEvidenceService {
    private final SourceItemRepository repository;

    public SourceEvidenceService(SourceItemRepository repository) {
        this.repository = repository;
    }

    public SourceEvidenceDTO latestForVacancy(long vacancyId) {
        return repository.latestForVacancy(vacancyId);
    }
}
