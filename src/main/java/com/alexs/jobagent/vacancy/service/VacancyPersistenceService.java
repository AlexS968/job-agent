package com.alexs.jobagent.vacancy.service;

import com.alexs.jobagent.vacancy.dto.VacancyDTO;
import com.alexs.jobagent.vacancy.dto.VacancyChangeDTO;
import com.alexs.jobagent.vacancy.entity.LiveVacancyEntity;
import com.alexs.jobagent.vacancy.entity.VacancyEntity;
import com.alexs.jobagent.vacancy.exception.VacancyConflictException;
import com.alexs.jobagent.vacancy.mapper.VacancyMapper;
import com.alexs.jobagent.vacancy.repository.EmployerRepository;
import com.alexs.jobagent.vacancy.repository.VacancyRepository;
import com.alexs.jobagent.vacancy.repository.VacancySourceRepository;
import java.time.Instant;
import java.util.Objects;
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

    public Result persist(VacancyDTO vacancy, long sourceItemId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Vacancy persistence requires an ingestion transaction");
        }
        long employerId = employers.findOrCreate(mapper.toEntity(vacancy.employer()));
        VacancyRepository.Result stored = vacancies.findOrCreate(mapper.toEntity(vacancy, employerId));
        links.link(stored.id(), sourceItemId);
        return new Result(stored.id(), stored.created());
    }

    public record Result(long vacancyId, boolean created) {
    }

    public LiveResult persistLive(VacancyDTO vacancy, long sourceItemId, String description,
                                  Instant updatedAt, String contentDigest) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Vacancy persistence requires an ingestion transaction");
        }
        long employerId = employers.findOrCreate(mapper.toEntity(vacancy.employer()));
        VacancyEntity candidate = mapper.toEntity(vacancy, employerId);
        Long inserted = vacancies.insertLiveIfAbsent(candidate, description, updatedAt, contentDigest);
        long vacancyId;
        VacancyChangeDTO change;
        if (inserted != null) {
            vacancyId = inserted;
            change = VacancyChangeDTO.CREATED;
        } else {
            // The insert conflict waits for a competing transaction; lock the committed row before deciding.
            LiveVacancyEntity current = vacancies.lockLive(candidate.sourceNamespace(), candidate.listingId());
            vacancyId = current.id();
            if (current.employerDbId() != candidate.employerDbId()) {
                throw new VacancyConflictException("Listing identity has conflicting employer identity");
            }
            if (current.sourceUpdatedAt() != null
                    && (updatedAt == null || updatedAt.isBefore(current.sourceUpdatedAt()))) {
                change = VacancyChangeDTO.STALE;
            } else if (Objects.equals(current.sourceUpdatedAt(), updatedAt)) {
                if (!contentDigest.equals(current.contentDigest())) {
                    throw new VacancyConflictException("Equal source version has conflicting content");
                }
                change = VacancyChangeDTO.UNCHANGED;
            } else {
                vacancies.updateLive(vacancyId, candidate, description, updatedAt, contentDigest);
                change = contentDigest.equals(current.contentDigest())
                        ? VacancyChangeDTO.UNCHANGED : VacancyChangeDTO.UPDATED;
            }
        }
        links.link(vacancyId, sourceItemId);
        return new LiveResult(vacancyId, change);
    }

    public record LiveResult(long vacancyId, VacancyChangeDTO change) {
    }
}
