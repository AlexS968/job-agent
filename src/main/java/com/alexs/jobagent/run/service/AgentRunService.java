package com.alexs.jobagent.run.service;

import com.alexs.jobagent.run.repository.AgentRunRepository;
import com.alexs.jobagent.run.dto.RunResultDTO;
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

    public void succeedLive(long runId, RunResultDTO change) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Run success requires an ingestion transaction");
        }
        repository.succeedLive(runId, change);
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Run start and failure tracking require no ambient transaction");
        }
    }
}
