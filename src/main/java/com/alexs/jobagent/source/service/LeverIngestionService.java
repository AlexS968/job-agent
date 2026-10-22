package com.alexs.jobagent.source.service;

import com.alexs.jobagent.outbox.service.OutboxService;
import com.alexs.jobagent.run.dto.RunResultDTO;
import com.alexs.jobagent.run.service.AgentRunService;
import com.alexs.jobagent.source.dto.LeverIngestionResultDTO;
import com.alexs.jobagent.source.dto.LeverListingDTO;
import com.alexs.jobagent.source.dto.LeverSiteDTO;
import com.alexs.jobagent.source.entity.SourceItemEntity;
import com.alexs.jobagent.source.exception.InvalidIngestionException;
import com.alexs.jobagent.source.mapper.LeverVacancyMapper;
import com.alexs.jobagent.source.repository.SourceItemRepository;
import com.alexs.jobagent.vacancy.dto.EmployerDTO;
import com.alexs.jobagent.vacancy.dto.VacancyChangeDTO;
import com.alexs.jobagent.vacancy.exception.VacancyConflictException;
import com.alexs.jobagent.vacancy.service.VacancyPersistenceService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LeverIngestionService {
    private final AgentRunService runs;
    private final SourceItemRepository sources;
    private final VacancyPersistenceService vacancies;
    private final OutboxService outbox;
    private final LeverVacancyMapper mapper;
    private final SourceContentDigest digest;
    private final TransactionTemplate transaction;

    public LeverIngestionService(AgentRunService runs, SourceItemRepository sources,
                                 VacancyPersistenceService vacancies, OutboxService outbox,
                                 LeverVacancyMapper mapper, SourceContentDigest digest,
                                 PlatformTransactionManager manager) {
        this.runs = runs;
        this.sources = sources;
        this.vacancies = vacancies;
        this.outbox = outbox;
        this.mapper = mapper;
        this.digest = digest;
        this.transaction = new TransactionTemplate(manager);
    }

    public LeverIngestionResultDTO ingest(LeverSiteDTO site, LeverListingDTO listing, Instant observationStartedAt) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Lever ingestion requires no ambient transaction");
        }
        long runId = runs.start();
        try {
            validate(site, listing, observationStartedAt);
            Instant storedObservationStartedAt = observationStartedAt.truncatedTo(ChronoUnit.MICROS);
            VacancyChangeDTO change = transaction.execute(status ->
                    ingestInTransaction(runId, site, listing, storedObservationStartedAt));
            return new LeverIngestionResultDTO(runId, change);
        } catch (RuntimeException failure) {
            try {
                runs.fail(runId, failureCode(failure));
            } catch (RuntimeException trackingFailure) {
                failure.addSuppressed(trackingFailure);
            }
            throw failure;
        }
    }

    private VacancyChangeDTO ingestInTransaction(long runId, LeverSiteDTO site, LeverListingDTO listing,
                                                  Instant observationStartedAt) {
        String namespace = "lever:" + site.region() + ":" + site.site();
        String contentDigest = digest.digest(listing.rawPayload(), Set.of());
        SourceItemEntity snapshot = new SourceItemEntity(namespace, listing.id() + ":" + contentDigest,
                namespace, listing.id(), listing.hostedUrl(), observationStartedAt,
                listing.rawPayload().toString(), "lever-postings-v1", "lever-normalization-v1", contentDigest);
        long sourceId = sources.insertOrFindObservedSnapshot(snapshot, listing.applyUrl(), contentDigest);
        EmployerDTO employer = new EmployerDTO("lever-site:" + site.region(), site.site(), site.employerName());
        VacancyPersistenceService.LiveResult stored = vacancies.persistObserved(
                mapper.toVacancy(listing, namespace, employer), sourceId, listing.description(),
                observationStartedAt, contentDigest);
        if (stored.change() == VacancyChangeDTO.CREATED) {
            outbox.vacancyDiscovered(stored.vacancyId(), runId);
        } else if (stored.change() == VacancyChangeDTO.UPDATED) {
            outbox.vacancyUpdated(stored.vacancyId(), runId);
        }
        runs.succeedLive(runId, RunResultDTO.valueOf(stored.change().name()));
        return stored.change();
    }

    private static void validate(LeverSiteDTO site, LeverListingDTO listing, Instant observedAt) {
        try {
            LeverSourceAdapter.validateSite(site);
            if (listing == null || observedAt == null || listing.rawPayload() == null
                    || !listing.rawPayload().isObject() || listing.id() == null
                    || listing.id().isBlank() || listing.id().length() > 120
                    || listing.title() == null || listing.title().isBlank() || listing.title().length() > 255
                    || listing.location() != null && listing.location().length() > 255
                    || listing.workplaceType() != null && listing.workplaceType().length() > 40
                    || listing.description() != null && listing.description().length() > 1_000_000
                    || listing.hostedUrl() == null || listing.hostedUrl().length() > 2048
                    || listing.applyUrl() != null && listing.applyUrl().length() > 2048) {
                throw new InvalidIngestionException("Invalid Lever listing");
            }
            LeverSourceAdapter.checkHostedUrl(listing.hostedUrl(), site);
            if (listing.applyUrl() != null) {
                LeverSourceAdapter.checkHostedUrl(listing.applyUrl(), site);
            }
        } catch (IllegalArgumentException invalid) {
            throw new InvalidIngestionException("Invalid Lever listing");
        }
    }

    private static String failureCode(RuntimeException failure) {
        if (failure instanceof InvalidIngestionException) {
            return "INVALID_INPUT";
        }
        if (failure instanceof VacancyConflictException) {
            return "IDENTITY_CONFLICT";
        }
        if (failure instanceof DataAccessException) {
            return "DATABASE_ERROR";
        }
        return "PROCESSING_ERROR";
    }
}
