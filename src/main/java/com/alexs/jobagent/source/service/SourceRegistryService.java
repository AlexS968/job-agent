package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.ActiveSourceImportResultDTO;
import com.alexs.jobagent.source.dto.SourceVerificationDTO;
import com.alexs.jobagent.source.dto.SourceDiscoveryCandidateDTO;
import com.alexs.jobagent.source.entity.VerifiedSourceEntity;
import com.alexs.jobagent.source.repository.SourceRegistryRepository;
import com.alexs.jobagent.vacancy.service.CandidateAssessmentService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SourceRegistryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SourceRegistryService.class);
    public static final Duration EVIDENCE_MAX_AGE = Duration.ofDays(30);
    private static final String VERIFIED_STATUS = "VERIFIED";
    private final SourceRegistryRepository repository;
    private final GreenhouseBatchService greenhouse;
    private final LeverBatchService lever;
    private final Clock clock;
    private final CandidateAssessmentService candidates;
    private final TransactionTemplate transaction;

    public SourceRegistryService(SourceRegistryRepository repository, GreenhouseBatchService greenhouse,
                                 LeverBatchService lever, CandidateAssessmentService candidates,
                                 Clock clock, PlatformTransactionManager manager) {
        this.repository = repository;
        this.greenhouse = greenhouse;
        this.lever = lever;
        this.candidates = candidates;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    public List<VerifiedSourceEntity> sources() { return repository.listSources(); }
    public List<SourceVerificationDTO> attempts() { return repository.listAttempts(); }
    public List<SourceDiscoveryCandidateDTO> discoveryCandidates(long afterId) {
        if (afterId < 0) {
            throw new IllegalArgumentException("Invalid discovery cursor");
        }
        return repository.listDiscoveryCandidates(afterId);
    }

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
        int unavailable = 0;
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        List<SourceRegistryRepository.SourceClaim> active =
                transaction.execute(status -> repository.reserveActiveBatch(5));
        for (SourceRegistryRepository.SourceClaim claim : active) {
            try {
                Optional<VerifiedSourceEntity> source = eligibleSource(claim.source().id());
                if (source.isEmpty()) {
                    failures++;
                    unavailable++;
                    continue;
                }
                sources++;
                var result = importSource(source.orElseThrow());
                failures += result.failures();
                unavailable += result.unavailableSources();
                created += result.created();
                updated += result.updated();
                unchanged += result.unchanged();
            } finally {
                repository.releaseImportClaim(claim.source().id(), claim.token());
            }
        }
        var assessed = candidates.assessPending();
        LOGGER.info("Candidate assessment: observed_listings={}, role_candidates={}, review={}, other_roles={}, policy={}",
                assessed.observed(), assessed.candidates(), assessed.review(), assessed.notCandidates(),
                com.alexs.jobagent.vacancy.service.CandidateAssessmentPolicy.VERSION);
        return new ActiveSourceImportResultDTO(sources, failures, created, updated, unchanged, unavailable);
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

    // Each call imports exactly one board. With no fetched listings, a failure precedes persistence.
    private ActiveSourceImportResultDTO importSource(VerifiedSourceEntity source) {
        if ("GREENHOUSE".equals(source.platform())) {
            var result = greenhouse.run(source.sourceId() + "|" + source.employerName());
            return new ActiveSourceImportResultDTO(0, result.failed() + result.rejected(),
                    result.created(), result.updated(), result.unchanged(), result.fetched() == 0 ? result.failed() : 0);
        }
        if ("LEVER".equals(source.platform())) {
            var result = lever.run(source.region() + "|" + source.sourceId() + "|" + source.employerName());
            return new ActiveSourceImportResultDTO(0, result.failed() + result.rejected(),
                    result.created(), result.updated(), result.unchanged(), result.fetched() == 0 ? result.failed() : 0);
        }
        return new ActiveSourceImportResultDTO(0, 1, 0, 0, 0);
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
