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
import org.jsoup.nodes.Document;
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
    private static final String AUTOMATED_OFFICIAL_PAGE = "automated-official-page";
    private static final String BOARD_RENEWAL = "automatic-board-renewal";
    private static final String RETRYABLE_FAILURE = "RETRYABLE_FAILURE";
    private static final String REJECTED = "REJECTED";
    private static final String AMBIGUOUS_LINKS = "AMBIGUOUS_LINKS";
    private static final String MIXED_PLATFORM_LINKS = "MIXED_PLATFORM_LINKS";
    private static final String BATCH_DEADLINE = "BATCH_DEADLINE";
    static final String EMPLOYER_NAME_UNCONFIRMED = "EMPLOYER_NAME_UNCONFIRMED";
    private static final String CONFLICTING_EMPLOYER_EVIDENCE = "CONFLICTING_EMPLOYER_EVIDENCE";
    private static final String HOMEPAGE_EXTERNAL_REDIRECT = "HOMEPAGE_EXTERNAL_REDIRECT";
    private static final String EXTERNAL_REDIRECT = "EXTERNAL_REDIRECT";
    private static final String TOO_MANY_LINKS = "TOO_MANY_LINKS";
    private static final String NO_SUPPORTED_LINK = "NO_SUPPORTED_LINK";
    private static final String UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM";
    private static final String UNSUPPORTED = "UNSUPPORTED";
    private static final String UNSAFE_OFFICIAL_PAGE = "UNSAFE_OFFICIAL_PAGE";
    private static final String REQUIRES_REVIEW = "REQUIRES_REVIEW";
    private static final String EQUAL_TIME_CONFLICT = "EQUAL_TIME_CONFLICT";
    private static final String BOARD_LINK_SELECTOR = "a[href],iframe[src],script[src]";
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
        String reason = MANUAL_REVIEW_OUTCOME.equals(completed.leadOutcome())
                && RETRYABLE_FAILURE.equals(result.outcome()) ? "RETRY_EXHAUSTED" : result.reason();
        LOGGER.info("Discovery verification: lead_id={}, stage={}, host={}, http_status={}, redirects={}, "
                        + "elapsed_ms={}, requests={}, retryable={}, links={}, link_limit={}, outcome={}, reason={}",
                claim.id(), trace.stage, trace.host, trace.httpStatus, trace.redirects,
                java.time.Duration.ofNanos(System.nanoTime() - started).toMillis(), result.requests(),
                RETRYABLE_FAILURE.equals(completed.leadOutcome()), trace.linkCount, trace.linkLimit,
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
            } else if (RETRYABLE_FAILURE.equals(evidence.outcome())) {
                retryable = true;
            } else if (AMBIGUOUS_LINKS.equals(evidence.reason())
                    || MIXED_PLATFORM_LINKS.equals(evidence.reason())) {
                conflict = true;
            }
        }
        if (conflict) {
            return automaticFailure(url, observed, MANUAL_REVIEW_OUTCOME,
                    CONFLICTING_EMPLOYER_EVIDENCE, evidenceRequests);
        }
        if (retryable) {
            return automaticFailure(url, observed, RETRYABLE_FAILURE, "EVIDENCE_FETCH_INCOMPLETE",
                    evidenceRequests);
        }
        if (matching != null) {
            return automaticResult(url, observed, matching);
        }
        if (lookupLimit != null) {
            return automaticFailure(url, observed, RETRYABLE_FAILURE, lookupLimit, evidenceRequests);
        }
        return automaticFailure(url, observed, MANUAL_REVIEW_OUTCOME,
                hosts.isEmpty() ? "NO_INDEPENDENT_EMPLOYER_EVIDENCE" : "ATS_ASSOCIATION_NOT_FOUND",
                evidenceRequests);
    }

    private SourceVerificationDTO automaticFailure(String url, Instant observed, String outcome,
                                                    String reason, int requests) {
        return result(new SourceCandidateDTO(url, AUTOMATED_OFFICIAL_PAGE, null, null, null),
                observed, outcome, reason, null, null, requests);
    }

    private SourceVerificationDTO automaticResult(String url, Instant observed, HostEvidence evidence) {
        return result(new SourceCandidateDTO(url, AUTOMATED_OFFICIAL_PAGE, evidence.domain(),
                evidence.pageUrl(), evidence.domain()), observed, evidence.outcome(), evidence.reason(),
                evidence.identity(), evidence.observedUrl(), evidence.requests());
    }

    /** Inspect source hints without persisting a board until the caller fences the vacancy lead. */
    SourceVerificationDTO inspectEmployer(String posting, String name, Set<String> hosts, AtsSourceIdentityDTO expected,
                                         Instant deadline) {
        Instant observed = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var trace = new VerificationTrace();
        trace.deadline = deadline;
        HostEvidence match = null;
        HostEvidence failure = null;
        int requests = 0;
        for (String host : hosts.stream().filter(candidate -> !isNonEmployerHost(candidate)).toList()) {
            var evidence = inspectHost(host, trace, name);
            requests += evidence.requests();
            if (blocksEmployerMatch(evidence)) {
                return employerResult(posting, name, observed, evidence, requests);
            }
            if (VERIFIED_OUTCOME.equals(evidence.outcome())) {
                if (conflictsWithMatch(expected, match, evidence)) {
                    return automaticFailure(posting, observed, MANUAL_REVIEW_OUTCOME,
                            CONFLICTING_EMPLOYER_EVIDENCE, requests);
                }
                match = evidence;
            } else if (preferFailure(failure, evidence)) {
                failure = evidence;
            }
        }
        HostEvidence selected = match == null ? failure : match;
        return selected == null
                ? automaticFailure(posting, observed, MANUAL_REVIEW_OUTCOME, EMPLOYER_NAME_UNCONFIRMED, requests)
                : employerResult(posting, name, observed, selected, requests);
    }

    private static boolean blocksEmployerMatch(HostEvidence evidence) {
        return AMBIGUOUS_LINKS.equals(evidence.reason()) || MIXED_PLATFORM_LINKS.equals(evidence.reason())
                || BATCH_DEADLINE.equals(evidence.reason());
    }

    private static boolean conflictsWithMatch(AtsSourceIdentityDTO expected, HostEvidence match, HostEvidence next) {
        return (expected != null && !expected.equals(next.identity()))
                || (match != null && !match.domain().equals(next.domain()));
    }

    private static boolean preferFailure(HostEvidence previous, HostEvidence next) {
        return !EMPLOYER_NAME_UNCONFIRMED.equals(next.reason())
                && (previous == null || RETRYABLE_FAILURE.equals(next.outcome()));
    }

    private SourceVerificationDTO employerResult(String posting, String name, Instant observed,
                                                 HostEvidence evidence, int requests) {
        return result(new SourceCandidateDTO(posting, AUTOMATED_OFFICIAL_PAGE, evidence.domain(),
                evidence.pageUrl(), name), observed, evidence.outcome(), evidence.reason(),
                evidence.identity(), evidence.observedUrl(), requests);
    }

    boolean renewOne(Instant deadline) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var claim = repository.claimRenewal(now, now.minus(SourceRegistryService.EVIDENCE_MAX_AGE));
        if (claim == null) {
            return false;
        }
        var source = claim.source();
        var observation = inspectRenewal(source, now, deadline);
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Source renewal interrupted");
        }
        transaction.executeWithoutResult(status -> {
            // Use the same domain-before-board lock order as initial verification.
            repository.findDecisionForUpdate(source.employerDomain());
            if (repository.lockCurrentRenewal(claim)) {
                persist(observation);
            }
        });
        return true;
    }

    private SourceVerificationDTO inspectRenewal(VerifiedSourceEntity source, Instant now, Instant deadline) {
        var candidate = new SourceCandidateDTO(source.evidenceUrl(), BOARD_RENEWAL,
                source.employerDomain(), source.officialPageUrl(), source.employerName());
        var expected = new AtsSourceIdentityDTO(source.platform(), source.region(), source.sourceId());
        var trace = new VerificationTrace();
        trace.deadline = deadline;
        try {
            URI uri = PublicDestinationPolicy.requirePublicHttpsUri(source.officialPageUrl());
            if (!uri.getHost().equalsIgnoreCase(source.employerDomain()) || isNonEmployerHost(uri.getHost())) {
                return result(candidate, now, MANUAL_REVIEW_OUTCOME, UNSAFE_OFFICIAL_PAGE, null, null, 0);
            }
            var page = fetchPage(uri.toString(), trace);
            return evaluate(candidate, expected, now, page);
        } catch (VerificationFetchException failure) {
            return result(candidate, now, failure.retryable() ? RETRYABLE_FAILURE : REJECTED,
                    failure.reason(), null, null, failure.requests());
        }
    }

    SourceVerificationDTO persistEmployer(SourceVerificationDTO observation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Employer association requires the lead transaction");
        }
        return persist(observation);
    }

    private HostEvidence inspectHost(String host, VerificationTrace trace, String advertisedName) {
        trace.stage = "HOMEPAGE";
        trace.host = host;
        trace.requests = 0;
        String domain = host;
        try {
            var home = fetchHomepage(host, trace);
            domain = home.pageUrl().getHost().toLowerCase(Locale.ROOT);
            var homepage = Jsoup.parse(home.html());
            if (!matchesAdvertisedName(homepage, advertisedName)) {
                return hostFailure(domain, MANUAL_REVIEW_OUTCOME, EMPLOYER_NAME_UNCONFIRMED, trace.requests);
            }
            var links = homepageCareers(home.pageUrl(), homepage, advertisedName != null, trace);
            return inspectCareers(domain, home.pageUrl(), links, trace);
        } catch (PageEvidenceException rejected) {
            return hostFailure(domain, MANUAL_REVIEW_OUTCOME, rejected.getMessage(), trace.requests);
        } catch (VerificationFetchException failure) {
            return hostFailure(host, failure.retryable() ? RETRYABLE_FAILURE : MANUAL_REVIEW_OUTCOME,
                    failure.reason(), trace.requests);
        }
    }

    private SafeHtmlFetcher.FetchResult fetchHomepage(String host, VerificationTrace trace)
            throws VerificationFetchException, PageEvidenceException {
        var home = fetchPage("https://" + host + "/", trace);
        if (home.externalRedirect() == null) {
            return home;
        }
        if (!wwwVariant(host, home.externalRedirect().getHost())) {
            throw new PageEvidenceException(HOMEPAGE_EXTERNAL_REDIRECT);
        }
        var redirected = fetchPage(home.externalRedirect().toString(), trace);
        if (redirected.externalRedirect() != null) {
            throw new PageEvidenceException(HOMEPAGE_EXTERNAL_REDIRECT);
        }
        return redirected;
    }

    private static boolean matchesAdvertisedName(Document homepage, String name) {
        return name == null || name.equalsIgnoreCase(homepage.title().strip())
                || homepage.select("meta[property=og:site_name]").stream()
                .anyMatch(meta -> name.equalsIgnoreCase(meta.attr("content").strip()));
    }

    private HomepageCareers homepageCareers(URI page, Document homepage, boolean allowDirectBoard,
                                            VerificationTrace trace) throws PageEvidenceException {
        var anchors = homepage.select("a[href]");
        checkLinkLimit(anchors.size(), trace);
        Set<URI> pages = new LinkedHashSet<>();
        Map<AtsSourceIdentityDTO, URI> boards = new LinkedHashMap<>();
        boolean unsupported = false;
        for (Element anchor : anchors) {
            URI link = safeLink(page, anchor.attr("href"));
            if (link == null || !isCareersLink(link, anchor.text())) {
                continue;
            }
            if (link.getHost().equalsIgnoreCase(page.getHost())) {
                pages.add(link);
            } else if (allowDirectBoard) {
                AtsSourceIdentityParser.parse(link).ifPresent(id -> boards.putIfAbsent(id, link));
                unsupported |= isKnownUnsupportedPlatform(link);
            }
        }
        return new HomepageCareers(pages, new LinkEvidenceDTO(boards, unsupported));
    }

    private HostEvidence inspectCareers(String domain, URI homepage, HomepageCareers careers, VerificationTrace trace)
            throws VerificationFetchException, PageEvidenceException {
        var boards = careers.direct().links();
        if (careers.pages().isEmpty() && boards.isEmpty()) {
            return hostFailure(domain, MANUAL_REVIEW_OUTCOME, "NO_HOMEPAGE_CAREERS_LINK", trace.requests);
        }
        if (careers.pages().size() > MAX_CAREERS_PAGES) {
            return hostFailure(domain, MANUAL_REVIEW_OUTCOME, "TOO_MANY_CAREERS_LINKS", trace.requests);
        }
        String officialPage = boards.isEmpty() ? null : homepage.toString();
        String inspectedPage = null;
        boolean unsupported = careers.direct().unsupported();
        for (URI url : careers.pages()) {
            trace.stage = "CAREERS";
            var page = fetchPage(url.toString(), trace);
            inspectedPage = page.pageUrl().toString();
            var evidence = careersPageEvidence(page, trace);
            evidence.links().forEach(boards::putIfAbsent);
            unsupported |= evidence.unsupported();
            if (officialPage == null && !evidence.links().isEmpty()) {
                officialPage = (page.externalRedirect() == null ? page.pageUrl() : url).toString();
            }
        }
        return careerAssociation(domain, boards, unsupported, inspectedPage, officialPage, trace.requests);
    }

    private LinkEvidenceDTO careersPageEvidence(SafeHtmlFetcher.FetchResult page, VerificationTrace trace)
            throws PageEvidenceException {
        URI redirect = page.externalRedirect();
        if (redirect != null) {
            var board = AtsSourceIdentityParser.parse(redirect)
                    .orElseThrow(() -> new PageEvidenceException(EXTERNAL_REDIRECT));
            return new LinkEvidenceDTO(Map.of(board, redirect), false);
        }
        var links = Jsoup.parse(page.html()).select(BOARD_LINK_SELECTOR);
        checkLinkLimit(links.size(), trace);
        return scanLinks(page.pageUrl(), links);
    }

    private static void checkLinkLimit(int count, VerificationTrace trace) throws PageEvidenceException {
        if (count > MAX_LINKS) {
            trace.tooManyLinks(count, MAX_LINKS);
            throw new PageEvidenceException(TOO_MANY_LINKS);
        }
    }

    private static HostEvidence careerAssociation(String domain, Map<AtsSourceIdentityDTO, URI> boards,
                                                  boolean unsupported, String inspectedPage, String officialPage,
                                                  int requests) {
        if (boards.size() > 1) {
            return new HostEvidence(domain, inspectedPage, null, MANUAL_REVIEW_OUTCOME, AMBIGUOUS_LINKS, null, requests);
        }
        if (unsupported || boards.isEmpty()) {
            String reason = unsupported ? MIXED_PLATFORM_LINKS : NO_SUPPORTED_LINK;
            return new HostEvidence(domain, inspectedPage, null, MANUAL_REVIEW_OUTCOME, reason, null, requests);
        }
        var board = boards.entrySet().iterator().next();
        return new HostEvidence(domain, officialPage, board.getValue().toString(),
                VERIFIED_OUTCOME, null, board.getKey(), requests);
    }

    private record HomepageCareers(Set<URI> pages, LinkEvidenceDTO direct) { }

    private static final class PageEvidenceException extends Exception {
        private PageEvidenceException(String reason) {
            super(reason);
        }
    }

    private static URI safeLink(URI page, String link) {
        try {
            return PublicDestinationPolicy.requirePublicHttpsUri(page.resolve(link).toString());
        } catch (IllegalArgumentException unsafe) {
            return null;
        }
    }

    private SafeHtmlFetcher.FetchResult fetchPage(String url, VerificationTrace trace)
            throws VerificationFetchException {
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Source verification interrupted");
            }
            if (clock.instant().plusSeconds(5).isAfter(trace.deadline)) {
                throw new VerificationFetchException(BATCH_DEADLINE, 0);
            }
            SafeHtmlFetcher.FetchResult result = fetcher.fetch(url);
            trace.requests += result.requests();
            trace.httpStatus = result.httpStatus();
            trace.redirects += result.redirects();
            return result;
        } catch (VerificationFetchException failure) {
            trace.requests += failure.requests();
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
            HostEvidence inspected = inspectHost(domain, trace, null);
            hosts.put(domain, inspected);
            hosts.putIfAbsent(inspected.domain(), inspected);
            return inspected;
        }
    }

    private record HostEvidence(String domain, String pageUrl, String observedUrl,
                                String outcome, String reason, AtsSourceIdentityDTO identity,
                                int requests) { }

    private static final class VerificationTrace {
        private Instant deadline = Instant.MAX;
        private String stage = "CLASSIFICATION";
        private String host = "unavailable";
        private Integer httpStatus;
        private int redirects;
        private int requests;
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
            return result(candidate, observed, REJECTED, "UNSAFE_URL", null, null, 0);
        }
        if (!Set.of("owner-confirmed", "independently-reviewed", AUTOMATED_OFFICIAL_PAGE)
                .contains(candidate.origin())) {
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME, "NO_TRUST_ROOT", null, null, 0);
        }
        URI official;
        try {
            official = PublicDestinationPolicy.requirePublicHttpsUri(candidate.officialPageUrl());
        } catch (RuntimeException unsafe) {
            return result(candidate, observed, REJECTED, UNSAFE_OFFICIAL_PAGE, null, null, 0);
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
            return evaluate(candidate, AtsSourceIdentityParser.parse(input).orElse(null), observed, page);
        } catch (VerificationFetchException failure) {
            return result(candidate, observed, RETRYABLE_FAILURE, failure.reason(), null, null, failure.requests());
        }
    }

    private SourceVerificationDTO evaluate(SourceCandidateDTO candidate, AtsSourceIdentityDTO expected, Instant observed,
                                           SafeHtmlFetcher.FetchResult page) {
        boolean renewal = BOARD_RENEWAL.equals(candidate.origin());
        if (page.externalRedirect() != null) {
            if (renewal && AtsSourceIdentityParser.parse(page.externalRedirect()).filter(expected::equals).isPresent()) {
                return result(candidate, observed, VERIFIED_OUTCOME, null, expected,
                        page.externalRedirect().toString(), page.requests());
            }
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME, EXTERNAL_REDIRECT, null,
                    page.externalRedirect().toString(), page.requests());
        }
        var anchors = Jsoup.parse(page.html()).select(BOARD_LINK_SELECTOR);
        if (anchors.size() > MAX_LINKS) {
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME, TOO_MANY_LINKS, null, null, page.requests());
        }
        LinkEvidenceDTO evidence = scanLinks(page.pageUrl(), anchors);
        Map<AtsSourceIdentityDTO, URI> links = evidence.links();
        if (links.isEmpty() && evidence.unsupported()) {
            return result(candidate, observed, UNSUPPORTED, UNSUPPORTED_PLATFORM, null, null, page.requests());
        }
        if (renewal && evidence.unsupported()) {
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME, MIXED_PLATFORM_LINKS, null, null, page.requests());
        }
        if (links.size() != 1) {
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME,
                    links.isEmpty() ? NO_SUPPORTED_LINK : AMBIGUOUS_LINKS,
                    null, null, page.requests());
        }
        var link = links.entrySet().iterator().next();
        if (expected != null && !expected.equals(link.getKey())) {
            return result(candidate, observed, MANUAL_REVIEW_OUTCOME, "IDENTITY_CONFLICT", expected,
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
                        pageUrl.resolve(anchor.attr(anchor.hasAttr("src") ? "src" : "href")).toString());
                if (!anchor.normalName().equals("a") && !(AtsSourceIdentityParser.isGreenhouse(
                        link.getHost().toLowerCase(Locale.ROOT)) && link.getPath().startsWith("/embed/"))) {
                    continue;
                }
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
        return host.equals("jobs.ashbyhq.com") || host.equals("jobs.smartrecruiters.com") || host.equals("apply.workable.com")
                || host.endsWith(".myworkdayjobs.com");
    }

    private SourceVerificationDTO result(SourceCandidateDTO candidate, Instant observed, String outcome,
                                         String reason, AtsSourceIdentityDTO identity, String observedUrl, int requests) {
        String domain = candidate.officialDomain() == null ? null
                : candidate.officialDomain().toLowerCase(Locale.ROOT);
        String evidence = (AUTOMATED_OFFICIAL_PAGE.equals(candidate.origin()) ? "" : candidate.inputUrl())
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
            return reclassify(result, REQUIRES_REVIEW);
        }
        if (result.observedAt().equals(previous.observedAt())) {
            if (!result.evidenceHash().equals(previous.evidenceHash())) {
                repository.updateDecision(result, CONFLICT_DECISION);
                repository.deactivateDomain(result.officialDomain(), result.observedAt(), true);
                return reclassify(result, EQUAL_TIME_CONFLICT);
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
            result = reclassify(result, REQUIRES_REVIEW);
        } else if (result.observedAt().equals(current.verifiedAt())
                && !result.evidenceHash().equals(current.evidenceHash())) {
            repository.markReview(current.id());
            repository.updateDecision(result, CONFLICT_DECISION);
            result = reclassify(result, EQUAL_TIME_CONFLICT);
        } else if (result.observedAt().isAfter(current.verifiedAt())) {
            boolean stillActive = VERIFIED_OUTCOME.equals(current.status()) && current.active()
                    && !current.verifiedAt().isBefore(result.observedAt().minus(SourceRegistryService.EVIDENCE_MAX_AGE));
            repository.refreshVerified(current.id(), result, stillActive);
        } else if (!VERIFIED_OUTCOME.equals(current.status())) {
            result = reclassify(result, SUPERSEDED_OBSERVATION);
        }
        if (inserted && AUTOMATED_OFFICIAL_PAGE.equals(result.origin())
                && VERIFIED_OUTCOME.equals(result.outcome())) {
            repository.activateAutomatically(current.id(), result.observedAt());
        }
        return result;
    }

    private boolean hasTrustedObservation(SourceVerificationDTO result) {
        if (AUTOMATED_OFFICIAL_PAGE.equals(result.origin()) && !VERIFIED_OUTCOME.equals(result.outcome())) {
            return false;
        }
        return result.officialDomain() != null && result.requests() > 0
                && (VERIFIED_OUTCOME.equals(result.outcome()) || MANUAL_REVIEW_OUTCOME.equals(result.outcome())
                || UNSUPPORTED.equals(result.outcome()));
    }

    private String decisionFor(SourceVerificationDTO result) {
        if (VERIFIED_OUTCOME.equals(result.outcome())) {
            return VERIFIED_OUTCOME;
        }
        if (NO_SUPPORTED_LINK.equals(result.reason()) || UNSUPPORTED_PLATFORM.equals(result.reason())) {
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
