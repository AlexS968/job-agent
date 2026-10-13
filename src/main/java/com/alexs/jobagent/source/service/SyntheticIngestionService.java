package com.alexs.jobagent.source.service;

import com.alexs.jobagent.run.service.AgentRunService;
import com.alexs.jobagent.outbox.service.OutboxService;
import com.alexs.jobagent.source.dto.IngestionResultDTO;
import com.alexs.jobagent.source.dto.SyntheticInputDTO;
import com.alexs.jobagent.source.entity.SourceItemEntity;
import com.alexs.jobagent.source.exception.IngestionConflictException;
import com.alexs.jobagent.source.exception.InvalidIngestionException;
import com.alexs.jobagent.source.repository.SourceItemRepository;
import com.alexs.jobagent.vacancy.dto.EmployerDTO;
import com.alexs.jobagent.vacancy.dto.VacancyDTO;
import com.alexs.jobagent.vacancy.exception.VacancyConflictException;
import com.alexs.jobagent.vacancy.service.VacancyPersistenceService;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SyntheticIngestionService {
    private final AgentRunService runs;
    private final SourceItemRepository sources;
    private final VacancyPersistenceService vacancyPersistence;
    private final OutboxService outbox;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public SyntheticIngestionService(AgentRunService runs, SourceItemRepository sources,
                                     VacancyPersistenceService vacancyPersistence, OutboxService outbox,
                                     JsonMapper json, PlatformTransactionManager manager) {
        this.runs = runs;
        this.sources = sources;
        this.vacancyPersistence = vacancyPersistence;
        this.outbox = outbox;
        this.json = json;
        this.transaction = new TransactionTemplate(manager);
    }

    public IngestionResultDTO ingest(SyntheticInputDTO input) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Synthetic ingestion requires no ambient transaction");
        }
        long runId = runs.start();
        try {
            validate(input);
            return transaction.execute(status -> ingestInTransaction(runId, input));
        } catch (RuntimeException failure) {
            try {
                runs.fail(runId, failureCode(failure));
            } catch (RuntimeException trackingFailure) {
                failure.addSuppressed(trackingFailure);
            }
            throw failure;
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

    private IngestionResultDTO ingestInTransaction(long runId, SyntheticInputDTO input) {
        String digest = digest(input);
        VacancyDTO vacancy = input.vacancy();
        SourceItemEntity item = new SourceItemEntity(input.sourceNamespace(), input.sourceItemId(),
                vacancy.sourceNamespace(), vacancy.listingId(), input.originalUrl(), input.fetchedAt(),
                input.rawPayload().toString(), input.inputVersion(), input.normalizationVersion(), digest);
        Long sourceId = sources.insertIfAbsent(item);
        if (sourceId == null) {
            if (!digest.equals(sources.digest(input.sourceNamespace(), input.sourceItemId()))) {
                throw new IngestionConflictException("Source item identity has changed content");
            }
            runs.succeed(runId, false);
            return new IngestionResultDTO(runId, false, false);
        }
        VacancyPersistenceService.Result stored;
        try {
            stored = vacancyPersistence.persist(vacancy, sourceId);
        } catch (VacancyConflictException conflict) {
            throw new IngestionConflictException(conflict.getMessage());
        }
        if (stored.created()) {
            outbox.vacancyDiscovered(stored.vacancyId(), runId);
        }
        runs.succeed(runId, true);
        return new IngestionResultDTO(runId, true, stored.created());
    }

    private String digest(SyntheticInputDTO input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void validate(SyntheticInputDTO input) {
        if (input == null || input.vacancy() == null || input.vacancy().employer() == null) {
            throw new InvalidIngestionException("Input, vacancy and employer are required");
        }
        VacancyDTO vacancy = input.vacancy();
        EmployerDTO employer = vacancy.employer();
        require(input.sourceNamespace(), 120);
        require(input.sourceItemId(), 200);
        require(input.inputVersion(), 40);
        require(input.normalizationVersion(), 40);
        require(vacancy.sourceNamespace(), 120);
        require(vacancy.listingId(), 200);
        require(vacancy.title(), 255);
        require(vacancy.location(), 255);
        require(employer.sourceNamespace(), 120);
        require(employer.employerId(), 200);
        require(employer.name(), 255);
        if (input.fetchedAt() == null || input.rawPayload() == null || !input.rawPayload().isObject()) {
            throw new InvalidIngestionException("Fetched timestamp and object payload are required");
        }
        if (input.originalUrl() == null || input.originalUrl().isBlank()) {
            throw new InvalidIngestionException("Source URL must be absolute HTTPS");
        }
        try {
            URI uri = URI.create(input.originalUrl());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new InvalidIngestionException("Source URL must be absolute HTTPS");
            }
        } catch (IllegalArgumentException invalidUrl) {
            throw new InvalidIngestionException("Source URL must be absolute HTTPS");
        }
    }

    private static void require(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new InvalidIngestionException("Required identity or normalized field is invalid");
        }
    }
}
