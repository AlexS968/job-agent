package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.repository.SearchPilotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SearchAttemptReservationService {
    private final SearchPilotRepository repository;
    private final TransactionTemplate transaction;

    public SearchAttemptReservationService(SearchPilotRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Long reserve(String queryVersion, int queryNumber) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Search reservation requires no ambient transaction");
        }
        return transaction.execute(status -> {
            if (!repository.incrementIfBelowLimit()) {
                return null;
            }
            return repository.insertAttempt(queryVersion, queryNumber);
        });
    }
}
