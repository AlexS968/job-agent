package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.AtsSourceIdentityDTO;
import com.alexs.jobagent.source.dto.SourceCandidateDTO;
import com.alexs.jobagent.source.dto.SourceVerificationDTO;
import com.alexs.jobagent.source.entity.VerifiedSourceEntity;
import com.alexs.jobagent.source.entity.VerificationDecisionEntity;
import com.alexs.jobagent.source.exception.VerificationFetchException;
import com.alexs.jobagent.source.repository.SourceRegistryRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SourceVerificationService {
    public static final int MAX_CANDIDATES = 5;
    private static final int MAX_LINKS = 200;
    private static final String VERIFIED_OUTCOME = "VERIFIED";
    private static final String MANUAL_REVIEW_OUTCOME = "MANUAL_REVIEW";
    private static final String CONFLICT_DECISION = "CONFLICT";
    private static final String SUPERSEDED_OBSERVATION = "SUPERSEDED_OBSERVATION";
    private static final Set<String> AGGREGATORS = Set.of("linkedin.com", "indeed.com", "glassdoor.com");
    private final SafeHtmlFetcher fetcher;
    private final SourceRegistryRepository repository;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SourceVerificationService(SafeHtmlFetcher fetcher, SourceRegistryRepository repository,
                                     Clock clock, PlatformTransactionManager manager) {
        this.fetcher = fetcher;
        this.repository = repository;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    public List<SourceVerificationDTO> verify(List<SourceCandidateDTO> candidates) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Verification requires no ambient transaction");
        }
        if (candidates == null || candidates.isEmpty() || candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("Verification requires one to five candidates");
        }
        List<SourceVerificationDTO> results = new ArrayList<>();
        for (SourceCandidateDTO candidate : candidates) {
            SourceVerificationDTO result = inspect(candidate);
            results.add(transaction.execute(status -> persist(result)));
        }
        return List.copyOf(results);
    }

    private SourceVerificationDTO inspect(SourceCandidateDTO candidate) {
        if (candidate == null || candidate.inputUrl() == null || candidate.inputUrl().length() > 2048
                || candidate.origin() == null || !Set.of("owner-confirmed", "independently-reviewed", "manual-candidate").contains(candidate.origin())) {
            throw new IllegalArgumentException("Invalid verification candidate");
        }
        Instant observed = clock.instant().truncatedTo(ChronoUnit.MICROS);
        URI input;
        try {
            input = PublicDestinationPolicy.requirePublicHttpsUri(candidate.inputUrl());
        } catch (IllegalArgumentException unsafe) {
            return result(candidate, observed, "REJECTED", "UNSAFE_URL", null, null, 0);
        }
        if (!Set.of("owner-confirmed", "independently-reviewed").contains(candidate.origin())) {
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME, "NO_TRUST_ROOT", null, null, 0);
        }
        URI official;
        try {
            official = PublicDestinationPolicy.requirePublicHttpsUri(candidate.officialPageUrl());
        } catch (RuntimeException unsafe) {
            return result(candidate, observed, "REJECTED", "UNSAFE_OFFICIAL_PAGE", null, null, 0);
        }
        if (candidate.officialDomain() == null || !official.getHost().equalsIgnoreCase(candidate.officialDomain())
                || AGGREGATORS.contains(official.getHost().toLowerCase(java.util.Locale.ROOT))
                || candidate.employerName() == null || candidate.employerName().isBlank()
                || candidate.employerName().length() > 255 || candidate.employerName().contains("|")
                || candidate.employerName().contains(",")) {
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME, "TRUST_ROOT_AMBIGUOUS", null, null, 0);
        }
        if (AtsSourceIdentityParser.parse(input).isEmpty()
                && !input.getHost().equalsIgnoreCase(official.getHost())) {
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME, "CANDIDATE_UNRELATED", null, null, 0);
        }
        try {
            SafeHtmlFetcher.FetchResult page = fetcher.fetch(official.toString());
            return evaluate(candidate, input, observed, page);
        } catch (VerificationFetchException failure) {
            return result(candidate, observed, "RETRYABLE_FAILURE", failure.reason(), null, null, failure.requests());
        }
    }

    private SourceVerificationDTO evaluate(SourceCandidateDTO candidate, URI input, Instant observed,
                                           SafeHtmlFetcher.FetchResult page) {
        if (page.externalRedirect() != null) {
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME, "EXTERNAL_REDIRECT", null,
                    page.externalRedirect().toString(), page.requests());
        }
        var anchors = Jsoup.parse(page.html()).select("a[href]");
        if (anchors.size() > MAX_LINKS) {
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME, "TOO_MANY_LINKS", null, null, page.requests());
        }
        LinkEvidenceDTO evidence = scanLinks(page.pageUrl(), anchors);
        Map<AtsSourceIdentityDTO, URI> links = evidence.links();
        if (links.isEmpty() && evidence.unsupported()) {
            return result(candidate, observed, "UNSUPPORTED", "UNSUPPORTED_PLATFORM", null, null, page.requests());
        }
        if (links.size() != 1) {
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME,
                    links.isEmpty() ? "NO_SUPPORTED_LINK" : "AMBIGUOUS_LINKS",
                    null, null, page.requests());
        }
        var link = links.entrySet().iterator().next();
        var inputIdentity = AtsSourceIdentityParser.parse(input);
        if (inputIdentity.isPresent() && !inputIdentity.get().equals(link.getKey())) {
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME, "IDENTITY_CONFLICT", inputIdentity.get(),
                    link.getValue().toString(), page.requests());
        }
        return result(candidate, observed, VERIFIED_OUTCOME, null, link.getKey(),
                link.getValue().toString(), page.requests());
    }

    private LinkEvidenceDTO scanLinks(URI pageUrl, Iterable<Element> anchors) {
        Map<AtsSourceIdentityDTO, URI> links = new LinkedHashMap<>();
        boolean unsupported = false;
        for (Element anchor : anchors) {
            try {
                URI link = PublicDestinationPolicy.requirePublicHttpsUri(
                        pageUrl.resolve(anchor.attr("href")).toString());
                AtsSourceIdentityParser.parse(link).ifPresent(id -> links.putIfAbsent(id, link));
                unsupported |= isKnownUnsupportedPlatform(link);
            } catch (IllegalArgumentException ignored) {
                // An unsafe link is never followed or used as association evidence.
            }
        }
        return new LinkEvidenceDTO(links, unsupported);
    }

    private record LinkEvidenceDTO(Map<AtsSourceIdentityDTO, URI> links, boolean unsupported) { }

    private boolean isKnownUnsupportedPlatform(URI link) {
        String host = link.getHost().toLowerCase(java.util.Locale.ROOT);
        return host.equals("jobs.smartrecruiters.com") || host.equals("apply.workable.com")
                || host.endsWith(".myworkdayjobs.com");
    }

    private SourceVerificationDTO result(SourceCandidateDTO candidate, Instant observed, String outcome,
                                         String reason, AtsSourceIdentityDTO identity, String observedUrl, int requests) {
        String domain = candidate.officialDomain() == null ? null
                : candidate.officialDomain().toLowerCase(Locale.ROOT);
        String evidence = candidate.inputUrl() + "|" + candidate.origin() + "|" + candidate.officialDomain()
                + "|" + candidate.officialPageUrl() + "|" + outcome + "|" + reason + "|" + observedUrl
                + "|" + identity;
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(evidence.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        return new SourceVerificationDTO(candidate.inputUrl(), candidate.origin(), domain,
                candidate.officialPageUrl(), observedUrl, observed, outcome, reason, identity,
                candidate.employerName(), requests, hash);
    }

    private SourceVerificationDTO persist(SourceVerificationDTO result) {
        if (!hasTrustedObservation(result)) {
            return saveResult(result);
        }

        String decision = decisionFor(result);
        SourceVerificationDTO blocked = orderDomainDecision(result, decision);
        if (blocked != null) {
            return saveResult(blocked);
        }
        if (!VERIFIED_OUTCOME.equals(result.outcome())) {
            repository.deactivateDomain(result.officialDomain(), result.observedAt(),
                    CONFLICT_DECISION.equals(decision));
            return saveResult(result);
        }
        return saveResult(persistVerified(result));
    }

    private SourceVerificationDTO orderDomainDecision(SourceVerificationDTO result, String decision) {
        boolean created = repository.insertDecisionIfAbsent(result, decision);
        VerificationDecisionEntity previous = repository.findDecisionForUpdate(result.officialDomain());
        if (created) {
            return null;
        }
        if (result.observedAt().isBefore(previous.observedAt())) {
            return reclassify(result, SUPERSEDED_OBSERVATION);
        }
        if (CONFLICT_DECISION.equals(previous.decision())) {
            if (result.observedAt().isAfter(previous.observedAt())) {
                repository.updateDecision(result, CONFLICT_DECISION);
            }
            return reclassify(result, "REQUIRES_REVIEW");
        }
        if (result.observedAt().equals(previous.observedAt())) {
            if (!result.evidenceHash().equals(previous.evidenceHash())) {
                repository.updateDecision(result, CONFLICT_DECISION);
                repository.deactivateDomain(result.officialDomain(), result.observedAt(), true);
                return reclassify(result, "EQUAL_TIME_CONFLICT");
            }
        } else {
            if (VERIFIED_OUTCOME.equals(decision) && previous.associatedPlatform() != null
                    && !sameAssociation(previous, result.identity())) {
                repository.updateDecision(result, CONFLICT_DECISION);
                repository.deactivateDomain(result.officialDomain(), result.observedAt(), true);
                return reclassify(result, "IDENTITY_CHANGE_CONFLICT");
            }
            repository.updateDecision(result, decision);
        }
        return null;
    }

    private boolean sameAssociation(VerificationDecisionEntity previous, AtsSourceIdentityDTO identity) {
        return previous.associatedPlatform().equals(identity.platform())
                && previous.associatedRegion().equals(identity.region())
                && previous.associatedSourceId().equals(identity.sourceId());
    }

    private SourceVerificationDTO persistVerified(SourceVerificationDTO result) {
        repository.insertVerified(result);
        VerifiedSourceEntity current = repository.findIdentityForUpdate(result.identity());
        if (current == null) {
            throw new IllegalStateException("Verified source missing after insert");
        }
        if (result.observedAt().isBefore(current.verifiedAt())) {
            return reclassify(result, SUPERSEDED_OBSERVATION);
        }
        if (!current.employerDomain().equalsIgnoreCase(result.officialDomain())) {
            repository.markReview(current.id());
            repository.updateDecision(result, CONFLICT_DECISION);
            result = reclassify(result, "IDENTITY_CLAIM_CONFLICT");
        } else if (MANUAL_REVIEW_OUTCOME.equals(current.status())) {
            repository.updateDecision(result, CONFLICT_DECISION);
            result = reclassify(result, "REQUIRES_REVIEW");
        } else if (result.observedAt().equals(current.verifiedAt())
                && !result.evidenceHash().equals(current.evidenceHash())) {
            repository.markReview(current.id());
            repository.updateDecision(result, CONFLICT_DECISION);
            result = reclassify(result, "EQUAL_TIME_CONFLICT");
        } else if (result.observedAt().isAfter(current.verifiedAt())) {
            boolean stillActive = VERIFIED_OUTCOME.equals(current.status()) && current.active()
                    && !current.verifiedAt().isBefore(result.observedAt().minus(SourceRegistryService.EVIDENCE_MAX_AGE));
            repository.refreshVerified(current.id(), result, stillActive);
        } else if (!VERIFIED_OUTCOME.equals(current.status())) {
            result = reclassify(result, SUPERSEDED_OBSERVATION);
        }
        return result;
    }

    private boolean hasTrustedObservation(SourceVerificationDTO result) {
        return result.officialDomain() != null && result.requests() > 0
                && (VERIFIED_OUTCOME.equals(result.outcome()) || MANUAL_REVIEW_OUTCOME.equals(result.outcome())
                || "UNSUPPORTED".equals(result.outcome()));
    }

    private String decisionFor(SourceVerificationDTO result) {
        if (VERIFIED_OUTCOME.equals(result.outcome())) {
            return VERIFIED_OUTCOME;
        }
        if ("NO_SUPPORTED_LINK".equals(result.reason()) || "UNSUPPORTED_PLATFORM".equals(result.reason())) {
            return "UNVERIFIED";
        }
        return CONFLICT_DECISION;
    }

    private SourceVerificationDTO saveResult(SourceVerificationDTO result) {
        repository.saveAttempt(result);
        return result;
    }

    private SourceVerificationDTO reclassify(SourceVerificationDTO original, String reason) {
        return result(new SourceCandidateDTO(original.inputUrl(), original.origin(), original.officialDomain(),
                        original.officialPageUrl(), original.employerName()), original.observedAt(),
                MANUAL_REVIEW_OUTCOME, reason, original.identity(), original.observedUrl(), original.requests());
    }
}
