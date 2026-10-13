package com.alexs.jobagent.outbox.service;

import com.alexs.jobagent.outbox.entity.OutboxEventEntity;
import com.alexs.jobagent.outbox.exception.UnsupportedEventContractException;
import com.alexs.jobagent.outbox.repository.ConsumerDeliveryRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SyntheticVacancyConsumer {
    private static final String CONSUMER_ID = "synthetic-vacancy-observer-v1";
    private final ConsumerDeliveryRepository repository;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SyntheticVacancyConsumer(ConsumerDeliveryRepository repository, Clock clock,
                                    PlatformTransactionManager manager) {
        this.repository = repository;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    public void handle(OutboxEventEntity event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Consumer handling requires no ambient transaction");
        }
        if (!"VacancyDiscovered".equals(event.eventType()) || event.schemaVersion() != 1) {
            throw new UnsupportedEventContractException();
        }
        transaction.executeWithoutResult(status -> {
            if (repository.recordDeliveryIfAbsent(CONSUMER_ID, event.eventId(), clock.instant())) {
                repository.observeVacancy(event.aggregateId(), event.eventId(), clock.instant());
            }
        });
    }
}
