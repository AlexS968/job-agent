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
        DispatchCounts counts = new DispatchCounts();
        String worker = UUID.randomUUID().toString();
        for (int i = 0; i < limit; i++) {
            ClaimResultDTO claim = outbox.claimWithRecovery(worker);
            counts.failed += claim.exhaustedClaims();
            OutboxEventEntity event = claim.event();
            if (event == null && claim.exhaustedClaims() == 0) {
                break;
            }
            if (event != null) {
                deliver(event, counts);
            }
        }
        return new DispatchResultDTO(counts.completed, counts.deferred, counts.failed, outbox.backlog());
    }

    private void deliver(OutboxEventEntity event, DispatchCounts counts) {
        try {
            consumer.handle(event);
            if (outbox.complete(event)) {
                counts.completed++;
            } else {
                counts.deferred++;
            }
        } catch (RuntimeException processingFailure) {
            recordFailure(event, processingFailure, counts);
        }
    }

    private void recordFailure(OutboxEventEntity event, RuntimeException processingFailure, DispatchCounts counts) {
        String code = processingFailure instanceof UnsupportedEventContractException
                ? "UNSUPPORTED_CONTRACT" : "HANDLER_ERROR";
        if (outbox.deferOrFail(event, code) && event.cycleAttempts() >= outbox.maxAttempts()) {
            counts.failed++;
        } else {
            counts.deferred++;
        }
    }

    private static final class DispatchCounts {
        private int completed;
        private int deferred;
        private int failed;
    }
}
