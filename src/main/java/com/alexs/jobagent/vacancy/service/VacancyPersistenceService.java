package com.alexs.jobagent.vacancy.service;

import com.alexs.jobagent.vacancy.dto.VacancyDTO;
import com.alexs.jobagent.vacancy.mapper.VacancyMapper;
import com.alexs.jobagent.vacancy.repository.EmployerRepository;
import com.alexs.jobagent.vacancy.repository.VacancyRepository;
import com.alexs.jobagent.vacancy.repository.VacancySourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class VacancyPersistenceService {
    private final EmployerRepository employers;
    private final VacancyRepository vacancies;
    private final VacancySourceRepository links;
    private final VacancyMapper mapper;

    public VacancyPersistenceService(EmployerRepository employers, VacancyRepository vacancies,
                                     VacancySourceRepository links, VacancyMapper mapper) {
        this.employers = employers;
        this.vacancies = vacancies;
        this.links = links;
        this.mapper = mapper;
    }

    public boolean persist(VacancyDTO vacancy, long sourceItemId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Vacancy persistence requires an ingestion transaction");
        }
        long employerId = employers.findOrCreate(mapper.toEntity(vacancy.employer()));
        VacancyRepository.Result stored = vacancies.findOrCreate(mapper.toEntity(vacancy, employerId));
        links.link(stored.id(), sourceItemId);
        return stored.created();
    }
}
