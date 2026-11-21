package com.alexs.jobagent.source.service;

import com.alexs.jobagent.outbox.service.OutboxService;
import com.alexs.jobagent.run.dto.RunResultDTO;
import com.alexs.jobagent.run.service.AgentRunService;
import com.alexs.jobagent.source.dto.HimalayasImportResultDTO;
import com.alexs.jobagent.source.dto.HimalayasListingDTO;
import com.alexs.jobagent.source.entity.SourceItemEntity;
import com.alexs.jobagent.source.repository.SourceItemRepository;
import com.alexs.jobagent.vacancy.dto.EmployerDTO;
import com.alexs.jobagent.vacancy.dto.VacancyChangeDTO;
import com.alexs.jobagent.vacancy.dto.VacancyDTO;
import com.alexs.jobagent.vacancy.exception.VacancyConflictException;
import com.alexs.jobagent.vacancy.service.CandidateAssessmentService;
import com.alexs.jobagent.vacancy.service.VacancyPersistenceService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HimalayasIntakeService {
    public static final String NAMESPACE = "himalayas";
    public static final String INPUT_VERSION = "himalayas-search-v1";
    private final HimalayasSearchAdapter search;
    private final AgentRunService runs;
    private final SourceItemRepository sources;
    private final VacancyPersistenceService vacancies;
    private final CandidateAssessmentService candidates;
    private final OutboxService outbox;
    private final SourceContentDigest digests;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public HimalayasIntakeService(HimalayasSearchAdapter search, AgentRunService runs,
                                  SourceItemRepository sources, VacancyPersistenceService vacancies,
                                  CandidateAssessmentService candidates, OutboxService outbox,
                                  SourceContentDigest digests, Clock clock, PlatformTransactionManager manager) {
        this.search = search;
        this.runs = runs;
        this.sources = sources;
        this.vacancies = vacancies;
        this.candidates = candidates;
        this.outbox = outbox;
        this.digests = digests;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    public HimalayasImportResultDTO run() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Himalayas intake requires no ambient transaction");
        }
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var batch = search.fetch();
        Map<String, HimalayasListingDTO> distinct = new LinkedHashMap<>();
        for (var posting : batch.listings()) {
            distinct.putIfAbsent(posting.postingId(), posting);
        }
        int roleRelevant = 0;
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int failed = batch.invalidRows();
        for (var posting : distinct.values()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Himalayas intake interrupted");
            }
            try {
                var outcome = persist(posting, observedAt);
                if (outcome.roleRelevant()) {
                    roleRelevant++;
                }
                switch (outcome.change()) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case UNCHANGED, STALE -> unchanged++;
                }
            } catch (VacancyConflictException conflict) {
                failed++;
            }
        }
        return new HimalayasImportResultDTO(batch.requests(), batch.fetched(), distinct.size(), roleRelevant,
                created, updated, unchanged, failed, batch.failedFilters());
    }

    private PostingOutcome persist(HimalayasListingDTO posting, Instant observedAt) {
        long runId = runs.start();
        try {
            return transaction.execute(status -> store(posting, observedAt, runId));
        } catch (RuntimeException failure) {
            try {
                runs.fail(runId, failureCode(failure));
            } catch (RuntimeException trackingFailure) {
                trackingFailure.addSuppressed(failure);
                throw trackingFailure;
            }
            throw failure;
        }
    }

    private PostingOutcome store(HimalayasListingDTO posting, Instant observedAt, long runId) {
        String digest = digests.digest(posting.evidence(), Set.of());
        var snapshot = new SourceItemEntity(NAMESPACE, posting.postingId() + ":" + digest,
                NAMESPACE, posting.postingId(), posting.postingUrl(), observedAt, posting.evidence().toString(),
                INPUT_VERSION, "himalayas-normalization-v1", digest);
        long sourceId = sources.insertOrFindObservedSnapshot(snapshot, posting.applicationLink(), digest);
        var employer = new EmployerDTO("himalayas:posting", posting.postingId(), "Employer not verified");
        var vacancy = new VacancyDTO(NAMESPACE, posting.postingId(), posting.title(),
                posting.advertisedLocation(), employer);
        var stored = vacancies.persistObserved(vacancy, sourceId, posting.description(), observedAt, digest);
        var assessment = candidates.assessObservation(sourceId, stored.vacancyId(), posting.title(),
                posting.description());
        if (stored.change() == VacancyChangeDTO.CREATED) {
            outbox.vacancyDiscovered(stored.vacancyId(), runId);
        } else if (stored.change() == VacancyChangeDTO.UPDATED) {
            outbox.vacancyUpdated(stored.vacancyId(), runId);
        }
        runs.succeedLive(runId, RunResultDTO.valueOf(stored.change().name()));
        return new PostingOutcome(stored.change(), "CANDIDATE".equals(assessment.decision()));
    }

    private static String failureCode(RuntimeException failure) {
        if (failure instanceof VacancyConflictException) {
            return "IDENTITY_CONFLICT";
        }
        return failure instanceof DataAccessException ? "DATABASE_ERROR" : "PROCESSING_ERROR";
    }

    private record PostingOutcome(VacancyChangeDTO change, boolean roleRelevant) { }
}
