package com.alexs.jobagent.outbox.service;

import com.alexs.jobagent.outbox.entity.OutboxEventEntity;
import com.alexs.jobagent.outbox.dto.BacklogDTO;
import com.alexs.jobagent.outbox.dto.ClaimResultDTO;
import com.alexs.jobagent.outbox.repository.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OutboxService {
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final int RECOVERY_BATCH_SIZE = 1;
    private final OutboxRepository repository;
    private final Clock clock;
    private final RetryPolicy retryPolicy;
    private final TransactionTemplate transaction;

    public OutboxService(OutboxRepository repository, Clock clock, RetryPolicy retryPolicy,
                         PlatformTransactionManager manager) {
        this.repository = repository;
        this.clock = clock;
        this.retryPolicy = retryPolicy;
        this.transaction = new TransactionTemplate(manager);
    }

    public void vacancyDiscovered(long vacancyId, long runId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Event creation requires the vacancy transaction");
        }
        repository.enqueue(UUID.randomUUID(), vacancyId, runId, clock.instant());
    }

    public void vacancyUpdated(long vacancyId, long runId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Event creation requires the vacancy transaction");
        }
        repository.enqueue(UUID.randomUUID(), "VacancyUpdated", vacancyId, runId, clock.instant());
    }

    public OutboxEventEntity claim(String worker) {
        return claimWithRecovery(worker).event();
    }

    public ClaimResultDTO claimWithRecovery(String worker) {
        requireNoAmbientTransaction();
        if (worker == null || worker.isBlank() || worker.length() > 120) {
            throw new IllegalArgumentException("Invalid worker identity");
        }
        Instant now = clock.instant();
        return transaction.execute(status -> {
            int exhausted = repository.expireExhaustedClaims(now, retryPolicy.maxAttempts(), RECOVERY_BATCH_SIZE);
            OutboxEventEntity event = repository.claim(worker, UUID.randomUUID(), now, now.plus(LEASE),
                    retryPolicy.maxAttempts());
            return new ClaimResultDTO(event, exhausted);
        });
    }

    public boolean complete(OutboxEventEntity event) {
        requireNoAmbientTransaction();
        return transaction.execute(status -> repository.complete(event.eventId(), event.claimToken(), clock.instant()));
    }

    public boolean deferOrFail(OutboxEventEntity event, String safeCode) {
        requireNoAmbientTransaction();
        Instant now = clock.instant();
        return transaction.execute(status -> repository.fail(event.eventId(), event.claimToken(), now,
                now.plus(retryPolicy.backoff(event.cycleAttempts())), retryPolicy.maxAttempts(), safeCode));
    }

    public boolean replayFailed(UUID eventId) {
        requireNoAmbientTransaction();
        return transaction.execute(status -> repository.replay(eventId, clock.instant()));
    }

    public BacklogDTO backlog() {
        requireNoAmbientTransaction();
        return repository.backlog(clock.instant());
    }

    public int maxAttempts() {
        return retryPolicy.maxAttempts();
    }

    private static void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox operations require no ambient transaction");
        }
    }
}
