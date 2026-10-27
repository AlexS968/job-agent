package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.ActiveSourceImportResultDTO;
import com.alexs.jobagent.source.dto.SourceVerificationDTO;
import com.alexs.jobagent.source.entity.VerifiedSourceEntity;
import com.alexs.jobagent.source.repository.SourceRegistryRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SourceRegistryService {
    public static final Duration EVIDENCE_MAX_AGE = Duration.ofDays(30);
    private final SourceRegistryRepository repository;
    private final GreenhouseBatchService greenhouse;
    private final LeverBatchService lever;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SourceRegistryService(SourceRegistryRepository repository, GreenhouseBatchService greenhouse,
                                 LeverBatchService lever, Clock clock, PlatformTransactionManager manager) {
        this.repository = repository;
        this.greenhouse = greenhouse;
        this.lever = lever;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    public List<VerifiedSourceEntity> sources() { return repository.listSources(); }
    public List<SourceVerificationDTO> attempts() { return repository.listAttempts(); }

    public boolean activate(long id) {
        requireNoAmbientTransaction();
        return Boolean.TRUE.equals(transaction.execute(status -> {
            VerifiedSourceEntity source = repository.findByIdForUpdate(id);
            if (!eligible(source)) {
                if (source != null && "VERIFIED".equals(source.status()) && !fresh(source)) {
                    repository.markStale(id);
                }
                return false;
            }
            repository.setActive(id, true);
            return true;
        }));
    }

    public ActiveSourceImportResultDTO importActive() {
        requireNoAmbientTransaction();
        int sources = 0;
        int failures = 0;
        List<VerifiedSourceEntity> active = repository.listActive();
        if (active.size() > 5) {
            throw new IllegalStateException("At most five active sources can be imported per invocation");
        }
        for (VerifiedSourceEntity listed : active) {
            VerifiedSourceEntity source = transaction.execute(status -> {
                VerifiedSourceEntity current = repository.findByIdForUpdate(listed.id());
                if (!eligible(current) || !current.active()) {
                    if (current != null && "VERIFIED".equals(current.status()) && !fresh(current)) {
                        repository.markStale(current.id());
                    }
                    return null;
                }
                return current;
            });
            if (source == null) {
                failures++;
                continue;
            }
            sources++;
            if ("GREENHOUSE".equals(source.platform())) {
                var result = greenhouse.run(source.sourceId() + "|" + source.employerName());
                failures += result.failed() + result.rejected();
            } else if ("LEVER".equals(source.platform())) {
                var result = lever.run(source.region() + "|" + source.sourceId() + "|" + source.employerName());
                failures += result.failed() + result.rejected();
            } else {
                failures++;
            }
        }
        return new ActiveSourceImportResultDTO(sources, failures);
    }

    private boolean eligible(VerifiedSourceEntity source) {
        return source != null && "VERIFIED".equals(source.status()) && fresh(source);
    }

    private boolean fresh(VerifiedSourceEntity source) {
        return !source.verifiedAt().isBefore(clock.instant().minus(EVIDENCE_MAX_AGE));
    }

    private void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Source registry operation requires no ambient transaction");
        }
    }
}
