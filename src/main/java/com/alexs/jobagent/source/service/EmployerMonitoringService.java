package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.AtsSourceIdentityDTO;
import com.alexs.jobagent.source.dto.DiscoveryBatchDTO;
import com.alexs.jobagent.source.dto.SourceVerificationDTO;
import com.alexs.jobagent.source.repository.EmployerLeadRepository;
import com.alexs.jobagent.source.repository.EmployerLeadRepository.Claim;
import com.alexs.jobagent.source.repository.SourceRegistryRepository;
import com.alexs.jobagent.vacancy.service.CandidateAssessmentPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmployerMonitoringService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmployerMonitoringService.class);
    private static final String MANUAL_REVIEW = "MANUAL_REVIEW";
    private static final String WAITING_FOR_SEARCH = "WAITING_FOR_SEARCH";
    private final EmployerLeadRepository leads;
    private final CandidateAssessmentPolicy policy;
    private final SearchPilotService search;
    private final SourceVerificationService verifier;
    private final SourceRegistryRepository registry;
    private final TransactionTemplate tx;
    private final Clock clock;

    public EmployerMonitoringService(EmployerLeadRepository leads, CandidateAssessmentPolicy policy,
                                    SearchPilotService search, SourceVerificationService verifier,
                                    SourceRegistryRepository registry, PlatformTransactionManager manager, Clock clock) {
        this.leads = leads;
        this.policy = policy;
        this.search = search;
        this.verifier = verifier;
        this.registry = registry;
        this.tx = new TransactionTemplate(manager);
        this.clock = clock;
    }

    /** Runs after primary intake and delivery. The previous discovery result carries the reviewed search allowance. */
    public void runUntil(Instant deadline, DiscoveryBatchDTO previous) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Employer discovery requires no ambient transaction");
        }
        if (!timeRemaining(deadline)) {
            return;
        }
        for (var row : leads.unseen()) {
            if (!timeRemaining(deadline)) {
                return;
            }
            var assessment = policy.assess(row.title(), row.description());
            leads.saveObservation(row, !"NOT_CANDIDATE".equals(assessment.decision()), assessment.reason());
        }
        int available = previous.enabled() && previous.failures() == 0 && !previous.monthlyLimitReached()
                ? 12 - previous.attempts() : 0;
        int renewed = timeRemaining(deadline) && verifier.renewOne(deadline) ? 1 : 0;
        for (int i = renewed; i < 2 && timeRemaining(deadline); i++) {
            var claim = leads.claim(available > 0);
            if (claim == null) {
                break;
            }
            available = resolve(claim, available, deadline);
        }
    }

    private int resolve(Claim claim, int available, Instant deadline) {
        if (claim.name().contains("|") || claim.name().contains(",")
                || claim.name().chars().anyMatch(Character::isISOControl)) {
            finish(claim, MANUAL_REVIEW, "UNSUPPORTED_EMPLOYER_NAME", null);
            return available;
        }
        var hosts = new LinkedHashSet<String>();
        var boards = new LinkedHashSet<AtsSourceIdentityDTO>();
        addHint(claim.site(), hosts, boards);
        addHint(claim.apply(), hosts, boards);
        if (boards.size() > 1) {
            finish(claim, MANUAL_REVIEW, "CONFLICTING_BOARD_HINTS", null);
            return available;
        }
        var board = boards.stream().findFirst().orElse(null);
        if (hosts.isEmpty() || claim.needsSearch()) {
            return resolveWithSearch(claim, available, deadline, board);
        }
        var result = verifier.inspectEmployer(claim.url(), claim.name(), hosts, board, deadline);
        if (SourceVerificationService.EMPLOYER_NAME_UNCONFIRMED.equals(result.reason())) {
            finish(claim, WAITING_FOR_SEARCH, "SITE_HINT_UNCONFIRMED", null);
        } else {
            finish(claim, result.outcome(), result.reason(), result);
        }
        return available;
    }

    private int resolveWithSearch(Claim claim, int available, Instant deadline, AtsSourceIdentityDTO board) {
        if (available <= 0 || !timeRemaining(deadline)) {
            finish(claim, WAITING_FOR_SEARCH, "SEARCH_UNAVAILABLE", null);
            return available;
        }
        var response = search.searchMonthlyEmployer(claim.name());
        if (response.monthlyLimitReached()) {
            finish(claim, WAITING_FOR_SEARCH, "MONTHLY_SEARCH_LIMIT", null);
            return 0;
        }
        if (response.failures() > 0 || response.cancelled()) {
            finish(claim, "RETRYABLE_FAILURE", "EMPLOYER_SEARCH_FAILED", null);
            return 0;
        }
        var hosts = new LinkedHashSet<String>();
        for (String url : response.unverifiedUrls()) {
            if (DiscoveryLeadPolicy.classify(url) == DiscoveryLeadPolicy.Kind.EMPLOYER) {
                hosts.add(employerHost(url));
            }
        }
        var result = verifier.inspectEmployer(claim.url(), claim.name(), hosts, board, deadline);
        finish(claim, result.outcome(), result.reason(), result);
        return available - response.attempts();
    }

    private static void addHint(String url, Set<String> hosts, Set<AtsSourceIdentityDTO> boards) {
        if (url == null) {
            return;
        }
        var kind = DiscoveryLeadPolicy.classify(url);
        if (kind == DiscoveryLeadPolicy.Kind.EMPLOYER || kind == DiscoveryLeadPolicy.Kind.ATS) {
            if (kind == DiscoveryLeadPolicy.Kind.ATS) {
                AtsSourceIdentityParser.parse(PublicDestinationPolicy.requirePublicHttpsUri(url)).ifPresent(boards::add);
            } else {
                hosts.add(employerHost(url));
            }
        }
    }

    private static String employerHost(String url) {
        return PublicDestinationPolicy.requirePublicHttpsUri(url).getHost().toLowerCase(Locale.ROOT);
    }

    private void finish(Claim claim, String status, String reason, SourceVerificationDTO result) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Employer discovery interrupted");
        }
        tx.executeWithoutResult(t -> {
            if (!leads.lock(claim)) {
                return;
            }
            if (result == null) {
                leads.finish(claim, status, reason, null, null);
            } else {
                var saved = verifier.persistEmployer(result);
                Long id = "VERIFIED".equals(saved.outcome()) ? registry.findIdentityForUpdate(saved.identity()).id() : null;
                leads.finish(claim, saved.outcome(), saved.reason(), id, saved.officialPageUrl());
            }
        });
        LOGGER.debug("Employer discovery processed lead_id={}", claim.id());
    }

    private boolean timeRemaining(Instant deadline) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Employer discovery interrupted");
        }
        // A Basic request is bounded at nine seconds, plus at most two seconds of retry-after handling.
        return clock.instant().plusSeconds(12).isBefore(deadline);
    }
}
