package com.alexs.jobagent.source.service;

import com.alexs.jobagent.outbox.service.OutboxService;
import com.alexs.jobagent.run.dto.RunResultDTO;
import com.alexs.jobagent.run.service.AgentRunService;
import com.alexs.jobagent.source.dto.LinkedInAlertDTO;
import com.alexs.jobagent.source.dto.LinkedInIntakeResultDTO;
import com.alexs.jobagent.source.exception.LinkedInAlertException;
import com.alexs.jobagent.source.entity.SourceItemEntity;
import com.alexs.jobagent.source.repository.GmailAlertRepository;
import com.alexs.jobagent.source.repository.SourceItemRepository;
import com.alexs.jobagent.vacancy.dto.EmployerDTO;
import com.alexs.jobagent.vacancy.dto.VacancyChangeDTO;
import com.alexs.jobagent.vacancy.dto.VacancyDTO;
import com.alexs.jobagent.vacancy.service.VacancyPersistenceService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class LinkedInIntakeService {
    private static final String NAMESPACE = "linkedin:gmail";
    private static final int MAX_CARDS_PER_RUN = 100;
    private final GmailReadClient gmail;
    private final LinkedInAlertParser parser;
    private final GmailAlertRepository messages;
    private final SourceItemRepository sources;
    private final VacancyPersistenceService vacancies;
    private final AgentRunService runs;
    private final OutboxService outbox;
    private final SourceContentDigest digests;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public LinkedInIntakeService(GmailReadClient gmail, LinkedInAlertParser parser, GmailAlertRepository messages,
                                 SourceItemRepository sources, VacancyPersistenceService vacancies,
                                 AgentRunService runs, OutboxService outbox, SourceContentDigest digests,
                                 JsonMapper json, PlatformTransactionManager manager) {
        this.gmail = gmail;
        this.parser = parser;
        this.messages = messages;
        this.sources = sources;
        this.vacancies = vacancies;
        this.runs = runs;
        this.outbox = outbox;
        this.digests = digests;
        this.json = json;
        this.transaction = new TransactionTemplate(manager);
    }

    public LinkedInIntakeResultDTO run(int maximum) {
        return run(maximum, null, null);
    }

    public LinkedInIntakeResultDTO run(int maximum, LocalDate after, LocalDate before) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("LinkedIn intake owns its transaction boundary");
        }
        if (maximum < 1 || maximum > 20) {
            throw new IllegalArgumentException("LinkedIn intake limit must be between 1 and 20");
        }
        if (after != null && before != null && !after.isBefore(before)) {
            throw new IllegalArgumentException("LinkedIn date window is invalid");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("LinkedIn intake interrupted");
        }
        int replayed = 0;
        int cards = 0;
        int created = 0;
        int updated = 0;
        int review = 0;
        int seen = 0;
        for (String id : gmail.alertIds(maximum, after, before)) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("LinkedIn intake interrupted");
            }
            seen++;
            String priorStatus = messages.status(id);
            if (priorStatus != null) {
                replayed++;
                review += "NEEDS_REVIEW".equals(priorStatus) ? 1 : 0;
                continue;
            }
            LinkedInAlertDTO alert;
            try {
                alert = parser.parse(gmail.alert(id));
            } catch (LinkedInAlertException unsupported) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("LinkedIn intake interrupted", unsupported);
                }
                long runId = runs.start();
                try {
                    MessageOutcome outcome = transaction.execute(status -> {
                        boolean inserted = messages.recordReview(id, unsupported.reason());
                        runs.succeedLive(runId, RunResultDTO.UNCHANGED);
                        return new MessageOutcome(!inserted, 0, 0, 0,
                                inserted || "NEEDS_REVIEW".equals(messages.status(id)));
                    });
                    replayed += outcome.replayed() ? 1 : 0;
                    review += outcome.review() ? 1 : 0;
                } catch (RuntimeException failure) {
                    failRun(runId, failure);
                    throw failure;
                }
                continue;
            }
            if (!id.equals(alert.messageId())) {
                throw new IllegalStateException("Gmail message identity mismatch");
            }
            if (cards + alert.cards().size() > MAX_CARDS_PER_RUN) {
                throw new IllegalStateException("LinkedIn intake card limit reached; earlier messages remain durable");
            }
            long runId = runs.start();
            try {
                MessageOutcome result = transaction.execute(status -> importMessage(alert, runId));
                replayed += result.replayed() ? 1 : 0;
                cards += result.cards();
                created += result.created();
                updated += result.updated();
                review += result.review() ? 1 : 0;
            } catch (RuntimeException failure) {
                failRun(runId, failure);
                throw failure;
            }
        }
        return new LinkedInIntakeResultDTO(seen, replayed, cards, created, updated, review);
    }

    private void failRun(long runId, RuntimeException failure) {
        try {
            runs.fail(runId, "PROCESSING_ERROR");
        } catch (RuntimeException trackingFailure) {
            failure.addSuppressed(trackingFailure);
        }
    }

    private MessageOutcome importMessage(LinkedInAlertDTO alert, long runId) {
        if (!messages.record(alert)) {
            runs.succeedLive(runId, RunResultDTO.UNCHANGED);
            return new MessageOutcome(true, 0, 0, 0,
                    "NEEDS_REVIEW".equals(messages.status(alert.messageId())));
        }
        int created = 0;
        int updated = 0;
        Instant observed = alert.observedAt().truncatedTo(ChronoUnit.MICROS);
        for (LinkedInAlertDTO.CardDTO card : alert.cards()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("LinkedIn intake interrupted");
            }
            JsonNode raw = json.readTree(json.writeValueAsString(Map.of(
                    "jobId", card.jobId(), "title", card.title(), "originalUrl", card.originalUrl())));
            String digest = digests.digest(raw, Set.of());
            SourceItemEntity snapshot = new SourceItemEntity(NAMESPACE,
                    alert.messageId() + ":" + card.jobId(), "linkedin", card.jobId(),
                    card.originalUrl(), observed, raw.toString(), "linkedin-alert-v1",
                    "linkedin-normalization-v1", digest);
            long sourceId = sources.insertOrFindObservedSnapshot(snapshot, null, digest);
            // The alert does not establish employer identity. Scope the placeholder to the job ID.
            EmployerDTO employer = new EmployerDTO("linkedin-unverified-job", card.jobId(), "Employer unknown");
            VacancyDTO vacancy = new VacancyDTO("linkedin", card.jobId(), card.title(), null, employer);
            VacancyPersistenceService.LiveResult stored = vacancies.persistObserved(
                    vacancy, sourceId, null, observed, digest,
                    !card.title().equals("LinkedIn job " + card.jobId()));
            if (stored.change() == VacancyChangeDTO.CREATED) {
                outbox.vacancyDiscovered(stored.vacancyId(), runId);
                created++;
            } else if (stored.change() == VacancyChangeDTO.UPDATED) {
                outbox.vacancyUpdated(stored.vacancyId(), runId);
                updated++;
            }
            messages.card(alert.messageId(), card, sourceId, stored.vacancyId(), stored.change().name());
        }
        runs.succeedLive(runId, created > 0 ? RunResultDTO.CREATED
                : updated > 0 ? RunResultDTO.UPDATED : RunResultDTO.UNCHANGED);
        return new MessageOutcome(false, alert.cards().size(), created, updated,
                alert.unsupportedLinks() > 0 || alert.cards().isEmpty());
    }

    private record MessageOutcome(boolean replayed, int cards, int created, int updated, boolean review) { }
}
