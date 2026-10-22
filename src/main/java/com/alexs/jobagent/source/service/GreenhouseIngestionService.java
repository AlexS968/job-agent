package com.alexs.jobagent.source.service;

import com.alexs.jobagent.outbox.service.OutboxService;
import com.alexs.jobagent.run.service.AgentRunService;
import com.alexs.jobagent.run.dto.RunResultDTO;
import com.alexs.jobagent.source.dto.GreenhouseBoardDTO;
import com.alexs.jobagent.source.dto.GreenhouseIngestionResultDTO;
import com.alexs.jobagent.source.dto.GreenhouseListingDTO;
import com.alexs.jobagent.source.entity.SourceItemEntity;
import com.alexs.jobagent.source.exception.IngestionConflictException;
import com.alexs.jobagent.source.exception.InvalidIngestionException;
import com.alexs.jobagent.source.mapper.GreenhouseVacancyMapper;
import com.alexs.jobagent.source.repository.SourceItemRepository;
import com.alexs.jobagent.vacancy.dto.EmployerDTO;
import com.alexs.jobagent.vacancy.exception.VacancyConflictException;
import com.alexs.jobagent.vacancy.dto.VacancyChangeDTO;
import com.alexs.jobagent.vacancy.service.VacancyPersistenceService;
import java.time.Instant;
import java.net.URI;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GreenhouseIngestionService {
    private final AgentRunService runs;
    private final SourceItemRepository sources;
    private final VacancyPersistenceService vacancies;
    private final OutboxService outbox;
    private final GreenhouseVacancyMapper mapper;
    private final SourceContentDigest contentDigest;
    private final TransactionTemplate transaction;

    public GreenhouseIngestionService(AgentRunService runs, SourceItemRepository sources,
                                     VacancyPersistenceService vacancies, OutboxService outbox,
                                     GreenhouseVacancyMapper mapper, SourceContentDigest contentDigest,
                                     PlatformTransactionManager manager) {
        this.runs = runs;
        this.sources = sources;
        this.vacancies = vacancies;
        this.outbox = outbox;
        this.mapper = mapper;
        this.contentDigest = contentDigest;
        this.transaction = new TransactionTemplate(manager);
    }

    public GreenhouseIngestionResultDTO ingest(GreenhouseBoardDTO board, GreenhouseListingDTO listing,
                                                Instant fetchedAt) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Greenhouse ingestion requires no ambient transaction");
        }
        long runId = runs.start();
        try {
            validate(board, listing, fetchedAt);
            VacancyChangeDTO change = transaction.execute(status ->
                    ingestInTransaction(runId, board, listing, fetchedAt));
            return new GreenhouseIngestionResultDTO(runId, change);
        } catch (RuntimeException failure) {
            try {
                runs.fail(runId, failureCode(failure));
            } catch (RuntimeException trackingFailure) {
                failure.addSuppressed(trackingFailure);
            }
            throw failure;
        }
    }

    private VacancyChangeDTO ingestInTransaction(long runId, GreenhouseBoardDTO board,
                                                              GreenhouseListingDTO listing, Instant fetchedAt) {
        String namespace = "greenhouse:" + board.token();
        String versionId = listing.id() + ":" + (listing.updatedAt() == null ? "undated" : listing.updatedAt());
        String digest = contentDigest.digest(listing.rawPayload(), Set.of("updated_at"));
        SourceItemEntity snapshot = new SourceItemEntity(namespace, versionId, namespace, listing.id(),
                listing.originalUrl(), fetchedAt, listing.rawPayload().toString(), "greenhouse-board-v1",
                "greenhouse-normalization-v1", digest);
        Long sourceId = sources.insertLiveSnapshot(snapshot, listing.updatedAt(), digest);
        if (sourceId == null) {
            if (!digest.equals(sources.liveDigest(namespace, versionId))) {
                throw new IngestionConflictException("Source version has conflicting content");
            }
            runs.succeedLive(runId, RunResultDTO.UNCHANGED);
            return VacancyChangeDTO.UNCHANGED;
        }
        EmployerDTO employer = new EmployerDTO("greenhouse-board", board.token(), board.employerName());
        VacancyPersistenceService.LiveResult stored = vacancies.persistLive(
                mapper.toVacancy(listing, namespace, employer), sourceId, listing.description(),
                listing.updatedAt(), digest);
        if (stored.change() == VacancyChangeDTO.CREATED) {
            outbox.vacancyDiscovered(stored.vacancyId(), runId);
        } else if (stored.change() == VacancyChangeDTO.UPDATED) {
            outbox.vacancyUpdated(stored.vacancyId(), runId);
        }
        runs.succeedLive(runId, RunResultDTO.valueOf(stored.change().name()));
        return stored.change();
    }

    private static void validate(GreenhouseBoardDTO board, GreenhouseListingDTO listing, Instant fetchedAt) {
        if (board == null || board.token() == null || !board.token().matches("[a-z0-9][a-z0-9-]{0,63}")
                || board.employerName() == null || board.employerName().isBlank()
                || board.employerName().length() > 255 || listing == null || fetchedAt == null
                || listing.rawPayload() == null || !listing.rawPayload().isObject()
                || listing.id() == null || !listing.id().matches("[0-9]+")
                || listing.title() == null || listing.title().isBlank() || listing.title().length() > 255
                || listing.location() != null && listing.location().length() > 255
                || listing.description() != null && listing.description().length() > 1_000_000
                || listing.originalUrl() == null || listing.originalUrl().length() > 2048) {
            throw new InvalidIngestionException("Invalid Greenhouse listing");
        }
        try {
            URI url = URI.create(listing.originalUrl());
            if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                    || url.getHost() == null || url.getUserInfo() != null) {
                throw new InvalidIngestionException("Invalid Greenhouse source URL");
            }
        } catch (IllegalArgumentException invalidUrl) {
            throw new InvalidIngestionException("Invalid Greenhouse source URL");
        }
    }

    private static String failureCode(RuntimeException failure) {
        if (failure instanceof InvalidIngestionException) {
            return "INVALID_INPUT";
        }
        if (failure instanceof IngestionConflictException || failure instanceof VacancyConflictException) {
            return "IDENTITY_CONFLICT";
        }
        if (failure instanceof DataAccessException) {
            return "DATABASE_ERROR";
        }
        return "PROCESSING_ERROR";
    }
}
