package com.alexs.jobagent.run.service;

import com.alexs.jobagent.run.repository.AgentRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AgentRunService {
    private final AgentRunRepository repository;

    public AgentRunService(AgentRunRepository repository) {
        this.repository = repository;
    }

    public long start() {
        requireNoTransaction();
        return repository.start();
    }

    public void succeed(long runId, boolean created) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Run success requires an ingestion transaction");
        }
        repository.succeed(runId, created);
    }

    public void fail(long runId, String errorCode) {
        requireNoTransaction();
        repository.fail(runId, errorCode);
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Run start and failure tracking require no ambient transaction");
        }
    }
}
