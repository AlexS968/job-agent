package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.AtsSourceIdentityDTO;
import com.alexs.jobagent.source.dto.SourceCandidateDTO;
import com.alexs.jobagent.source.dto.SourceVerificationDTO;
import com.alexs.jobagent.source.dto.DiscoveryClaimDTO;
import com.alexs.jobagent.source.dto.AutomaticVerificationDTO;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SourceVerificationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SourceVerificationService.class);
    public static final int MAX_CANDIDATES = 5;
    private static final int MAX_LINKS = 200;
    private static final int MAX_CAREERS_PAGES = 3;
    private static final int MAX_EVIDENCE_HOSTS = 3;
    private static final String VERIFIED_OUTCOME = "VERIFIED";
    private static final String MANUAL_REVIEW_OUTCOME = "MANUAL_REVIEW";
    private static final String CONFLICT_DECISION = "CONFLICT";
    private static final String SUPERSEDED_OBSERVATION = "SUPERSEDED_OBSERVATION";
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
            results.add(transaction.execute(status -> {
                SourceVerificationDTO persisted = persist(result);
                repository.recordDiscoveryVerification(persisted);
                return persisted;
            }));
        }
        return List.copyOf(results);
    }

    public SourceVerificationDTO verifyStored(long id, String origin, String officialDomain,
                                               String officialPageUrl, String employerName) {
        if (id < 1) {
            throw new IllegalArgumentException("Invalid discovery candidate ID");
        }
        String url = repository.discoveryCandidateUrl(id);
        if (url == null) {
            throw new IllegalArgumentException("Discovery candidate is unavailable");
        }
        return verify(List.of(new SourceCandidateDTO(url, origin, officialDomain,
                officialPageUrl, employerName))).getFirst();
    }

    /** Fetches independent same-domain homepage evidence before the existing ATS association check. */
    public AutomaticVerificationDTO verifyAutomatic(DiscoveryClaimDTO claim) {
        return verifyAutomatic(claim, List.of(), null, newAutomaticSession());
    }

    public AutomaticSession newAutomaticSession() {
        return new AutomaticSession();
    }

    public AutomaticVerificationDTO verifyAutomatic(DiscoveryClaimDTO claim, List<String> evidenceUrls,
                                                    String lookupLimit, AutomaticSession session) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || claim == null) {
            throw new IllegalStateException("Automatic verification requires a committed discovery claim");
        }
        if (evidenceUrls == null || evidenceUrls.size() > MAX_EVIDENCE_HOSTS || session == null) {
            throw new IllegalArgumentException("Invalid bounded discovery evidence");
        }
        var trace = new VerificationTrace();
        long started = System.nanoTime();
        SourceVerificationDTO result = inspectAutomatic(claim.candidateUrl(), evidenceUrls,
                lookupLimit, session, trace);
        AutomaticVerificationDTO completed = transaction.execute(status -> {
            Instant now = clock.instant();
            if (!repository.claimValidForUpdate(claim, now)) {
                throw new IllegalStateException("Discovery claim expired or was superseded");
            }
            boolean wasActive = repository.activeIdentity(result.identity());
            SourceVerificationDTO persisted = persist(result);
            String leadOutcome = repository.finishDiscoveryClaim(claim, persisted, now);
            return new AutomaticVerificationDTO(persisted, leadOutcome,
                    !wasActive && repository.activeIdentity(persisted.identity()));
        });
        String reason = "MANUAL_REVIEW".equals(completed.leadOutcome())
                && "RETRYABLE_FAILURE".equals(result.outcome()) ? "RETRY_EXHAUSTED" : result.reason();
        LOGGER.info("Discovery verification: lead_id={}, stage={}, host={}, http_status={}, redirects={}, "
                        + "elapsed_ms={}, requests={}, retryable={}, links={}, link_limit={}, outcome={}, reason={}",
                claim.id(), trace.stage, trace.host, trace.httpStatus, trace.redirects,
                java.time.Duration.ofNanos(System.nanoTime() - started).toMillis(), result.requests(),
                "RETRYABLE_FAILURE".equals(completed.leadOutcome()), trace.linkCount, trace.linkLimit,
                completed.leadOutcome(), reason);
        return completed;
    }

    private SourceVerificationDTO inspectAutomatic(String url, List<String> evidenceUrls,
                                                   String lookupLimit, AutomaticSession session,
                                                   VerificationTrace trace) {
        trace.stage = "CLASSIFICATION";
        Instant observed = clock.instant().truncatedTo(ChronoUnit.MICROS);
        URI input;
        try {
            input = PublicDestinationPolicy.requirePublicHttpsUri(url);
        } catch (IllegalArgumentException unsafe) {
            return automaticFailure(url, observed, MANUAL_REVIEW_OUTCOME, "UNSAFE_URL", 0);
        }
        trace.host = input.getHost().toLowerCase(Locale.ROOT);
        var board = AtsSourceIdentityParser.parse(input);
        if (board.isPresent()) {
            trace.stage = "ATS_EVIDENCE";
            return inspectAtsLead(url, board.get(), evidenceUrls, lookupLimit, observed, session, trace);
        }
        String host = input.getHost().toLowerCase(Locale.ROOT);
        if (isNonEmployerHost(host)) {
            return automaticFailure(url, observed, MANUAL_REVIEW_OUTCOME,
                    "NO_INDEPENDENT_EMPLOYER_SITE", 0);
        }
        return automaticResult(url, observed, session.host(host, trace));
    }

    private SourceVerificationDTO inspectAtsLead(String url, AtsSourceIdentityDTO board,
                                                  List<String> evidenceUrls, String lookupLimit,
                                                  Instant observed, AutomaticSession session,
                                                  VerificationTrace trace) {
        HostEvidence matching = null;
        boolean conflict = false;
        boolean retryable = false;
        int evidenceRequests = 0;
        Set<String> hosts = new LinkedHashSet<>();
        for (String evidenceUrl : evidenceUrls) {
            try {
                String host = PublicDestinationPolicy.requirePublicHttpsUri(evidenceUrl)
                        .getHost().toLowerCase(Locale.ROOT);
                if (!isNonEmployerHost(host)) {
                    hosts.add(host);
                }
            } catch (IllegalArgumentException ignored) {
                // Search output is a lead, never a trust root.
            }
        }
        for (String host : hosts) {
            HostEvidence evidence = session.host(host, trace);
            evidenceRequests += evidence.requests();
            if (VERIFIED_OUTCOME.equals(evidence.outcome())) {
                if (!board.equals(evidence.identity())) {
                    conflict = true;
                } else if (matching == null) {
                    matching = evidence;
                } else if (!matching.domain().equals(evidence.domain())) {
                    conflict = true;
                }
            } else if ("RETRYABLE_FAILURE".equals(evidence.outcome())) {
                retryable = true;
            } else if ("AMBIGUOUS_LINKS".equals(evidence.reason())
                    || "MIXED_PLATFORM_LINKS".equals(evidence.reason())) {
                conflict = true;
            }
        }
        if (conflict) {
            return automaticFailure(url, observed, MANUAL_REVIEW_OUTCOME,
                    "CONFLICTING_EMPLOYER_EVIDENCE", evidenceRequests);
        }
        if (retryable) {
            return automaticFailure(url, observed, "RETRYABLE_FAILURE", "EVIDENCE_FETCH_INCOMPLETE",
                    evidenceRequests);
        }
        if (matching != null) {
            return automaticResult(url, observed, matching);
        }
        if (lookupLimit != null) {
            return automaticFailure(url, observed, "RETRYABLE_FAILURE", lookupLimit, evidenceRequests);
        }
        return automaticFailure(url, observed, MANUAL_REVIEW_OUTCOME,
                hosts.isEmpty() ? "NO_INDEPENDENT_EMPLOYER_EVIDENCE" : "ATS_ASSOCIATION_NOT_FOUND",
                evidenceRequests);
    }

    private SourceVerificationDTO automaticFailure(String url, Instant observed, String outcome,
                                                    String reason, int requests) {
        return result(new SourceCandidateDTO(url, "automated-official-page", null, null, null),
                observed, outcome, reason, null, null, requests);
    }

    private SourceVerificationDTO automaticResult(String url, Instant observed, HostEvidence evidence) {
        return result(new SourceCandidateDTO(url, "automated-official-page", evidence.domain(),
                evidence.pageUrl(), evidence.domain()), observed, evidence.outcome(), evidence.reason(),
                evidence.identity(), evidence.observedUrl(), evidence.requests());
    }

    private HostEvidence inspectHost(String host, VerificationTrace trace) {
        int requests = 0;
        try {
            trace.stage = "HOMEPAGE";
            trace.host = host;
            SafeHtmlFetcher.FetchResult home = fetchPage("https://" + host + "/", trace);
            requests += home.requests();
            if (home.externalRedirect() != null) {
                if (!wwwVariant(host, home.externalRedirect().getHost())) {
                    return hostFailure(host, MANUAL_REVIEW_OUTCOME, "HOMEPAGE_EXTERNAL_REDIRECT", requests);
                }
                home = fetchPage(home.externalRedirect().toString(), trace);
                requests += home.requests();
                if (home.externalRedirect() != null) {
                    return hostFailure(host, MANUAL_REVIEW_OUTCOME, "HOMEPAGE_EXTERNAL_REDIRECT", requests);
                }
            }
            String domain = home.pageUrl().getHost().toLowerCase(Locale.ROOT);
            var anchors = Jsoup.parse(home.html()).select("a[href]");
            if (anchors.size() > MAX_LINKS) {
                trace.tooManyLinks(anchors.size(), MAX_LINKS);
                return hostFailure(domain, MANUAL_REVIEW_OUTCOME, "TOO_MANY_LINKS", requests);
            }
            Set<URI> careers = new LinkedHashSet<>();
            for (Element anchor : anchors) {
                try {
                    URI link = PublicDestinationPolicy.requirePublicHttpsUri(
                            home.pageUrl().resolve(anchor.attr("href")).toString());
                    if (link.getHost().equalsIgnoreCase(domain) && isCareersLink(link, anchor.text())) {
                        careers.add(link);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Unsafe links cannot establish careers-page evidence.
                }
            }
            if (careers.isEmpty()) {
                return hostFailure(domain, MANUAL_REVIEW_OUTCOME, "NO_HOMEPAGE_CAREERS_LINK", requests);
            }
            if (careers.size() > MAX_CAREERS_PAGES) {
                return hostFailure(domain, MANUAL_REVIEW_OUTCOME, "TOO_MANY_CAREERS_LINKS", requests);
            }
            Map<AtsSourceIdentityDTO, URI> boards = new LinkedHashMap<>();
            String officialPage = null;
            boolean unsupported = false;
            for (URI careersUrl : careers) {
                trace.stage = "CAREERS";
                SafeHtmlFetcher.FetchResult page = fetchPage(careersUrl.toString(), trace);
                requests += page.requests();
                if (page.externalRedirect() != null) {
                    var redirected = AtsSourceIdentityParser.parse(page.externalRedirect());
                    if (redirected.isEmpty()) {
                        return hostFailure(domain, MANUAL_REVIEW_OUTCOME, "EXTERNAL_REDIRECT", requests);
                    }
                    boards.putIfAbsent(redirected.get(), page.externalRedirect());
                    if (officialPage == null) {
                        officialPage = careersUrl.toString();
                    }
                } else {
                    var pageAnchors = Jsoup.parse(page.html()).select("a[href]");
                    if (pageAnchors.size() > MAX_LINKS) {
                        trace.tooManyLinks(pageAnchors.size(), MAX_LINKS);
                        return hostFailure(domain, MANUAL_REVIEW_OUTCOME, "TOO_MANY_LINKS", requests);
                    }
                    LinkEvidenceDTO links = scanLinks(page.pageUrl(), pageAnchors);
                    boards.putAll(links.links());
                    unsupported |= links.unsupported();
                    if (officialPage == null && !links.links().isEmpty()) {
                        officialPage = page.pageUrl().toString();
                    }
                }
            }
            if (boards.size() != 1 || unsupported) {
                String reason = boards.size() > 1 ? "AMBIGUOUS_LINKS"
                        : unsupported ? "MIXED_PLATFORM_LINKS" : "NO_SUPPORTED_LINK";
                return hostFailure(domain, MANUAL_REVIEW_OUTCOME, reason, requests);
            }
            var board = boards.entrySet().iterator().next();
            return new HostEvidence(domain, officialPage, board.getValue().toString(),
                    VERIFIED_OUTCOME, null, board.getKey(), requests);
        } catch (VerificationFetchException failure) {
            return hostFailure(host, failure.retryable() ? "RETRYABLE_FAILURE" : MANUAL_REVIEW_OUTCOME,
                    failure.reason(), requests + failure.requests());
        }
    }

    private SafeHtmlFetcher.FetchResult fetchPage(String url, VerificationTrace trace)
            throws VerificationFetchException {
        try {
            SafeHtmlFetcher.FetchResult result = fetcher.fetch(url);
            trace.httpStatus = result.httpStatus();
            trace.redirects += result.redirects();
            return result;
        } catch (VerificationFetchException failure) {
            trace.httpStatus = failure.httpStatus();
            trace.redirects += failure.redirects();
            throw failure;
        }
    }

    private static boolean isCareersLink(URI link, String label) {
        String path = link.getPath().toLowerCase(Locale.ROOT);
        String text = label.trim().toLowerCase(Locale.ROOT);
        return path.matches(".*/(?:careers?|jobs|opportunities|open-positions|join-us|work-with-us)/?")
                || Set.of("careers", "career", "jobs", "open positions", "open roles",
                        "opportunities", "join us", "work with us").contains(text);
    }

    private static boolean wwwVariant(String original, String redirect) {
        return redirect != null && (redirect.equalsIgnoreCase("www." + original)
                || original.equalsIgnoreCase("www." + redirect));
    }

    private static boolean isNonEmployerHost(String host) {
        return host.equals("greenhouse.io") || host.endsWith(".greenhouse.io")
                || host.equals("lever.co") || host.endsWith(".lever.co")
                || DiscoveryLeadPolicy.isExcludedEmployerHost(host);
    }

    private static HostEvidence hostFailure(String domain, String outcome, String reason, int requests) {
        return new HostEvidence(domain, null, null, outcome, reason, null, requests);
    }

    public final class AutomaticSession {
        private final Map<String, HostEvidence> hosts = new LinkedHashMap<>();

        private HostEvidence host(String domain, VerificationTrace trace) {
            HostEvidence existing = hosts.get(domain);
            if (existing != null) {
                trace.stage = "CACHED_HOST_EVIDENCE";
                trace.host = domain;
                return new HostEvidence(existing.domain(), existing.pageUrl(), existing.observedUrl(),
                        existing.outcome(), existing.reason(), existing.identity(), 0);
            }
            HostEvidence inspected = inspectHost(domain, trace);
            hosts.put(domain, inspected);
            hosts.putIfAbsent(inspected.domain(), inspected);
            return inspected;
        }
    }

    private record HostEvidence(String domain, String pageUrl, String observedUrl,
                                String outcome, String reason, AtsSourceIdentityDTO identity,
                                int requests) { }

    private static final class VerificationTrace {
        private String stage = "CLASSIFICATION";
        private String host = "unavailable";
        private Integer httpStatus;
        private int redirects;
        private Integer linkCount;
        private Integer linkLimit;

        private void tooManyLinks(int count, int limit) {
            linkCount = count;
            linkLimit = limit;
        }
    }

    private SourceVerificationDTO inspect(SourceCandidateDTO candidate) {
        if (candidate == null || candidate.inputUrl() == null || candidate.inputUrl().length() > 2048
                || candidate.origin() == null || !Set.of("owner-confirmed", "independently-reviewed",
                "manual-candidate").contains(candidate.origin())) {
            throw new IllegalArgumentException("Invalid verification candidate");
        }
        Instant observed = clock.instant().truncatedTo(ChronoUnit.MICROS);
        URI input;
        try {
            input = PublicDestinationPolicy.requirePublicHttpsUri(candidate.inputUrl());
        } catch (IllegalArgumentException unsafe) {
            return result(candidate, observed, "REJECTED", "UNSAFE_URL", null, null, 0);
        }
        if (!Set.of("owner-confirmed", "independently-reviewed", "automated-official-page")
                .contains(candidate.origin())) {
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME, "NO_TRUST_ROOT", null, null, 0);
        }
        URI official;
        try {
            official = PublicDestinationPolicy.requirePublicHttpsUri(candidate.officialPageUrl());
        } catch (RuntimeException unsafe) {
            return result(candidate, observed, "REJECTED", "UNSAFE_OFFICIAL_PAGE", null, null, 0);
        }
        if (candidate.officialDomain() == null || !official.getHost().equalsIgnoreCase(candidate.officialDomain())
                || DiscoveryLeadPolicy.isExcludedEmployerHost(official.getHost())
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
        String evidence = ("automated-official-page".equals(candidate.origin()) ? "" : candidate.inputUrl())
                + "|" + candidate.origin() + "|" + candidate.officialDomain()
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
        boolean inserted = repository.insertVerified(result);
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
        if (inserted && "automated-official-page".equals(result.origin())
                && VERIFIED_OUTCOME.equals(result.outcome())) {
            repository.setActive(current.id(), true);
        }
        return result;
    }

    private boolean hasTrustedObservation(SourceVerificationDTO result) {
        if ("automated-official-page".equals(result.origin()) && !VERIFIED_OUTCOME.equals(result.outcome())) {
            return false;
        }
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
