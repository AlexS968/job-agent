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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;
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
        return process(gmail.alertIds(maximum, after, before), null, null);
    }

    LinkedInIntakeResultDTO process(List<String> ids, Long liveStart, Long liveEnd) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("LinkedIn intake owns its transaction boundary");
        }
        int replayed = 0;
        int cards = 0;
        int created = 0;
        int updated = 0;
        int review = 0;
        for (String id : ids) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("LinkedIn intake interrupted");
            }
            MessageOutcome result = processMessage(id, liveStart, liveEnd, MAX_CARDS_PER_RUN - cards);
            replayed += result.replayed() ? 1 : 0;
            cards += result.cards();
            created += result.created();
            updated += result.updated();
            review += result.review() ? 1 : 0;
        }
        return new LinkedInIntakeResultDTO(ids.size(), replayed, cards, created, updated, review);
    }

    private MessageOutcome processMessage(String id, Long liveStart, Long liveEnd, int remainingCards) {
        String priorStatus = messages.status(id);
        if (priorStatus != null) {
            return new MessageOutcome(true, 0, 0, 0, "NEEDS_REVIEW".equals(priorStatus));
        }
        LinkedInAlertDTO alert;
        try {
            alert = parser.parse(gmail.alert(id));
        } catch (LinkedInAlertException unsupported) {
            return recordReview(id, unsupported);
        }
        if (!id.equals(alert.messageId())) {
            throw new IllegalStateException("Gmail message identity mismatch");
        }
        long observed = alert.observedAt().getEpochSecond();
        if (liveStart != null && (observed < liveStart || observed >= liveEnd)) {
            return new MessageOutcome(false, 0, 0, 0, false);
        }
        if (alert.cards().size() > remainingCards) {
            throw new IllegalStateException("LinkedIn intake card limit reached; earlier messages remain durable");
        }
        return persist(runId -> {
            MessageOutcome outcome = importMessage(alert, runId);
            if (liveStart != null && !outcome.replayed() && !outcome.review()) {
                messages.enqueueLiveAction(id);
            }
            return outcome;
        });
    }

    private MessageOutcome recordReview(String id, LinkedInAlertException unsupported) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("LinkedIn intake interrupted", unsupported);
        }
        return persist(runId -> {
            boolean inserted = messages.recordReview(id, unsupported.reason());
            runs.succeedLive(runId, RunResultDTO.UNCHANGED);
            return new MessageOutcome(!inserted, 0, 0, 0,
                    inserted || "NEEDS_REVIEW".equals(messages.status(id)));
        });
    }

    private MessageOutcome persist(LongFunction<MessageOutcome> work) {
        long runId = runs.start();
        try {
            return transaction.execute(status -> work.apply(runId));
        } catch (RuntimeException failure) {
            failRun(runId, failure);
            throw failure;
        }
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
