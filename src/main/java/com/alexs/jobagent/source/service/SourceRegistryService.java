package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.ActiveSourceImportResultDTO;
import com.alexs.jobagent.source.dto.SourceVerificationDTO;
import com.alexs.jobagent.source.entity.VerifiedSourceEntity;
import com.alexs.jobagent.source.repository.SourceRegistryRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SourceRegistryService {
    public static final Duration EVIDENCE_MAX_AGE = Duration.ofDays(30);
    private static final String VERIFIED_STATUS = "VERIFIED";
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
                if (source != null && VERIFIED_STATUS.equals(source.status()) && !fresh(source)) {
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
            Optional<VerifiedSourceEntity> source = eligibleSource(listed.id());
            if (source.isEmpty()) {
                failures++;
                continue;
            }
            sources++;
            failures += importSource(source.orElseThrow());
        }
        return new ActiveSourceImportResultDTO(sources, failures);
    }

    private Optional<VerifiedSourceEntity> eligibleSource(long id) {
        return transaction.execute(status -> {
            VerifiedSourceEntity current = repository.findByIdForUpdate(id);
            if (current == null) {
                return Optional.empty();
            }
            if (!eligible(current) || !current.active()) {
                if (VERIFIED_STATUS.equals(current.status()) && !fresh(current)) {
                    repository.markStale(id);
                }
                return Optional.empty();
            }
            return Optional.of(current);
        });
    }

    private int importSource(VerifiedSourceEntity source) {
        if ("GREENHOUSE".equals(source.platform())) {
            var result = greenhouse.run(source.sourceId() + "|" + source.employerName());
            return result.failed() + result.rejected();
        }
        if ("LEVER".equals(source.platform())) {
            var result = lever.run(source.region() + "|" + source.sourceId() + "|" + source.employerName());
            return result.failed() + result.rejected();
        }
        return 1;
    }

    private boolean eligible(VerifiedSourceEntity source) {
        return source != null && VERIFIED_STATUS.equals(source.status()) && fresh(source);
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
