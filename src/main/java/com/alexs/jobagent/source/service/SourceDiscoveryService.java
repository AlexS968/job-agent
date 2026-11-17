package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.AtsSourceIdentityDTO;
import com.alexs.jobagent.source.dto.DiscoveryBatchDTO;
import com.alexs.jobagent.source.dto.DiscoveryClaimDTO;
import com.alexs.jobagent.source.dto.SearchPilotResultDTO;
import com.alexs.jobagent.source.dto.SourceVerificationDTO;
import com.alexs.jobagent.source.repository.SourceRegistryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SourceDiscoveryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SourceDiscoveryService.class);
    private final SearchPilotService search;
    private final SourceVerificationService verifier;
    private final SourceRegistryRepository repository;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final String reviewedOn;

    public SourceDiscoveryService(SearchPilotService search, SourceVerificationService verifier,
                                  SourceRegistryRepository repository, Clock clock,
                                  PlatformTransactionManager manager,
                                  @Value("${JOBAGENT_DISCOVERY_ENABLED:false}") boolean enabled,
                                  @Value("${JOBAGENT_DISCOVERY_REVIEWED_ON:}") String reviewedOn) {
        this.search = search;
        this.verifier = verifier;
        this.repository = repository;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.enabled = enabled;
        this.reviewedOn = reviewedOn;
    }

    /** Shared bounded workflow for the scheduled task and the controlled one-off batch mode. */
    public DiscoveryBatchDTO run() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Discovery requires no ambient transaction");
        }
        if (!enabled) {
            return new DiscoveryBatchDTO(false, 0, 0, 0, 0, 0, 0, 0, 0, false);
        }
        requireRecentBillingReview();
        SearchPilotResultDTO searched = search.runMonthly();
        int queries = searched.queries();
        int attempts = searched.attempts();
        int failures = searched.failures();
        boolean monthlyLimit = searched.monthlyLimitReached();
        boolean searchStopped = searched.failures() > 0 || searched.cancelled();
        Set<String> leads = new LinkedHashSet<>(searched.unverifiedUrls());
        Map<AtsSourceIdentityDTO, List<String>> boardEvidence = new HashMap<>();
        Map<AtsSourceIdentityDTO, String> lookupLimits = new HashMap<>();
        var resolution = verifier.newAutomaticSession();
        List<DiscoveryClaimDTO> claims = transaction.execute(status ->
                repository.claimDiscoveryBatch(clock.instant(), 5));
        int confirmed = 0;
        int activated = 0;
        int review = 0;
        int retryable = 0;
        for (DiscoveryClaimDTO claim : claims) {
            AtsSourceIdentityDTO board = atsIdentity(claim.candidateUrl());
            String lookupLimit = null;
            if (board != null && !boardEvidence.containsKey(board)) {
                if (attempts >= 12) {
                    lookupLimit = "INVOCATION_SEARCH_LIMIT";
                } else if (monthlyLimit) {
                    lookupLimit = "MONTHLY_SEARCH_LIMIT";
                } else if (searchStopped) {
                    lookupLimit = "EVIDENCE_SEARCH_UNAVAILABLE";
                } else {
                    SearchPilotResultDTO lookup = search.searchMonthlyBoardEvidence(board, 12 - attempts);
                    queries += lookup.queries();
                    attempts += lookup.attempts();
                    failures += lookup.failures();
                    searchStopped |= lookup.failures() > 0 || lookup.cancelled();
                    monthlyLimit |= lookup.monthlyLimitReached();
                    leads.addAll(lookup.unverifiedUrls());
                    boardEvidence.put(board, lookup.unverifiedUrls());
                    if (lookup.monthlyLimitReached()) {
                        lookupLimit = "MONTHLY_SEARCH_LIMIT";
                    } else if (lookup.failures() > 0 && lookup.unverifiedUrls().isEmpty()) {
                        lookupLimit = "EVIDENCE_SEARCH_FAILED";
                    }
                }
                boardEvidence.putIfAbsent(board, List.of());
                if (lookupLimit != null) {
                    lookupLimits.put(board, lookupLimit);
                }
            }
            var verified = verifier.verifyAutomatic(claim,
                    board == null ? List.of() : boardEvidence.getOrDefault(board, List.of()),
                    board == null ? null : lookupLimits.get(board), resolution);
            SourceVerificationDTO result = verified.verification();
            if ("VERIFIED".equals(verified.leadOutcome())) {
                confirmed++;
                if (verified.activated()) {
                    activated++;
                }
            } else if ("RETRYABLE_FAILURE".equals(verified.leadOutcome())) {
                retryable++;
            } else {
                review++;
            }
            LOGGER.info("Discovery lead: id={}, outcome={}, reason={}, identity={}, activated={}",
                    claim.id(), verified.leadOutcome(),
                    "MANUAL_REVIEW".equals(verified.leadOutcome())
                            && "RETRYABLE_FAILURE".equals(result.outcome()) ? "RETRY_EXHAUSTED" : result.reason(),
                    result.identity(), verified.activated());
        }
        DiscoveryBatchDTO summary = new DiscoveryBatchDTO(true, queries, attempts,
                leads.size(), confirmed, activated, review, retryable, failures, monthlyLimit);
        LOGGER.info("Discovery summary: queries={}, attempts={}, leads={}, confirmed={}, activated={}, "
                        + "review={}, retryable={}, failures={}, monthly_limit={}",
                summary.queries(), summary.attempts(), summary.leads(), summary.confirmed(),
                summary.activated(), summary.review(), summary.retryable(), summary.failures(),
                summary.monthlyLimitReached());
        return summary;
    }

    private static AtsSourceIdentityDTO atsIdentity(String url) {
        try {
            return AtsSourceIdentityParser.parse(PublicDestinationPolicy.requirePublicHttpsUri(url)).orElse(null);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private void requireRecentBillingReview() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        try {
            LocalDate reviewed = LocalDate.parse(reviewedOn);
            if (reviewed.isAfter(today) || reviewed.isBefore(today.minusDays(30))) {
                throw new IllegalArgumentException("Discovery billing review is stale");
            }
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Discovery requires a recent reviewed billing gate", invalid);
        }
    }
}
