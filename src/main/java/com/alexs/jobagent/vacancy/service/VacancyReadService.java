package com.alexs.jobagent.vacancy.service;

import com.alexs.jobagent.vacancy.dto.VacancyReadDTO;
import com.alexs.jobagent.vacancy.repository.VacancyRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class VacancyReadService {
    private final VacancyRepository repository;

    public VacancyReadService(VacancyRepository repository) {
        this.repository = repository;
    }

    public List<Long> idsAfter(long id, int limit) {
        return repository.idsAfter(id, limit);
    }

    public VacancyReadDTO read(long id) {
        return repository.readForEvaluation(id, false);
    }

    public VacancyReadDTO lock(long id) {
        return repository.readForEvaluation(id, true);
    }
}
