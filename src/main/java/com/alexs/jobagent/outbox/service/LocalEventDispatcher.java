package com.alexs.jobagent.outbox.service;

import com.alexs.jobagent.outbox.dto.DispatchResultDTO;
import com.alexs.jobagent.outbox.dto.ClaimResultDTO;
import com.alexs.jobagent.outbox.entity.OutboxEventEntity;
import com.alexs.jobagent.outbox.exception.UnsupportedEventContractException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LocalEventDispatcher {
    private final OutboxService outbox;
    private final SyntheticVacancyConsumer consumer;

    public LocalEventDispatcher(OutboxService outbox, SyntheticVacancyConsumer consumer) {
        this.outbox = outbox;
        this.consumer = consumer;
    }

    public DispatchResultDTO dispatch(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Dispatch limit must be between 1 and 100");
        }
        int completed = 0;
        int deferred = 0;
        int failed = 0;
        String worker = UUID.randomUUID().toString();
        for (int i = 0; i < limit; i++) {
            ClaimResultDTO claim = outbox.claimWithRecovery(worker);
            failed += claim.exhaustedClaims();
            OutboxEventEntity event = claim.event();
            if (event == null) {
                if (claim.exhaustedClaims() == 0) {
                    break;
                }
                continue;
            }
            try {
                consumer.handle(event);
                if (outbox.complete(event)) {
                    completed++;
                } else {
                    deferred++;
                }
            } catch (RuntimeException processingFailure) {
                String code = processingFailure instanceof UnsupportedEventContractException
                        ? "UNSUPPORTED_CONTRACT" : "HANDLER_ERROR";
                if (outbox.deferOrFail(event, code)) {
                    if (event.cycleAttempts() >= outbox.maxAttempts()) {
                        failed++;
                    } else {
                        deferred++;
                    }
                } else {
                    deferred++;
                }
            }
        }
        return new DispatchResultDTO(completed, deferred, failed, outbox.backlog());
    }
}
