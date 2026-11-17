package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alexs.jobagent.source.dto.GreenhouseFetchResultDTO;
import com.alexs.jobagent.source.dto.GreenhouseListingDTO;
import com.alexs.jobagent.source.dto.SearchResponseDTO;
import com.alexs.jobagent.source.dto.SearchResultDTO;
import com.alexs.jobagent.source.exception.SearchFailureException;
import com.alexs.jobagent.source.repository.SearchPilotRepository;
import com.alexs.jobagent.source.repository.SourceRegistryRepository;
import com.alexs.jobagent.source.service.GreenhouseBatchService;
import com.alexs.jobagent.source.service.GreenhouseIngestionService;
import com.alexs.jobagent.source.service.LeverBatchService;
import com.alexs.jobagent.source.service.SafeHtmlFetcher;
import com.alexs.jobagent.source.service.SearchAttemptReservationService;
import com.alexs.jobagent.source.service.SearchGateway;
import com.alexs.jobagent.source.service.SearchPilotService;
import com.alexs.jobagent.source.service.SourceDiscoveryService;
import com.alexs.jobagent.source.service.SourceRegistryService;
import com.alexs.jobagent.source.service.SourceVerificationService;
import com.alexs.jobagent.source.service.DiscoveryLeadPolicy;
import com.alexs.jobagent.vacancy.service.CandidateAssessmentService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class SourceDiscoveryIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("job_agent_discovery_test").withUsername("test_user")
            .withPassword("synthetic_test_secret_123");
    private static final String CAREERS = "https://acme.example/careers";
    private static final String ATS = "https://job-boards.greenhouse.io/acme/jobs/42";
    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired GreenhouseIngestionService greenhouseIngestion;
    @Autowired LeverBatchService lever;
    @Autowired CandidateAssessmentService assessments;
    @Autowired JsonMapper json;

    @BeforeEach
    void clear() {
        jdbc.execute("""
                TRUNCATE source_discovery_candidates, search_pilot_attempts, search_monthly_budget,
                source_verification_attempts, source_verification_decisions, verified_source_registry,
                active_source_import_claims, vacancy_sources, source_items, vacancies, employers,
                agent_runs RESTART IDENTITY CASCADE
                """);
        jdbc.update("UPDATE active_source_import_cursor SET last_source_id = 0");
    }

    @Test
    void retainedLeadsClaimAtsThenEmployerWithoutSpendingSlotsOnAggregators() {
        long attempt = jdbc.queryForObject("""
                INSERT INTO search_pilot_attempts (query_version, query_number, outcome)
                VALUES ('synthetic-test', 1, 'SUCCESS') RETURNING id
                """, Long.class);
        var search = new SearchPilotRepository(jdbc);
        for (String url : List.of("https://wellfound.com/jobs/1", "https://in.linkedin.com/jobs/2",
                "https://remotefront.com/jobs/3", "https://en.devjobs.de/jobs/4",
                "https://en.devjobs.at/jobs/5", "https://www.greenhouse.com/",
                "https://tavily.com/", "https://job-boards.greenhouse.io/",
                "https://jobs.lever.co/Bad_ID")) {
            var kind = DiscoveryLeadPolicy.classify(url);
            search.recordCandidate(attempt, url, kind.name(), kind.priority());
        }
        for (String url : List.of("https://acme.example/roles/42", ATS,
                "https://jobs.lever.co/acme/42", "https://careers.greenhouse.com.example/jobs")) {
            var kind = DiscoveryLeadPolicy.classify(url);
            search.recordCandidate(attempt, url, kind.name(), kind.priority());
        }
        var claims = new org.springframework.transaction.support.TransactionTemplate(manager)
                .execute(status -> new SourceRegistryRepository(jdbc).claimDiscoveryBatch(Clock.systemUTC().instant(), 5));
        assertEquals(List.of(ATS, "https://jobs.lever.co/acme/42",
                        "https://acme.example/roles/42", "https://careers.greenhouse.com.example/jobs"),
                claims.stream().map(com.alexs.jobagent.source.dto.DiscoveryClaimDTO::candidateUrl).toList());
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM source_discovery_candidates "
                + "WHERE verification_reason = 'AGGREGATOR_ONLY_LEAD' AND verification_attempts = 0", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM source_discovery_candidates "
                + "WHERE verification_reason = 'NON_EMPLOYER_SITE' AND verification_attempts = 0", Integer.class));
        assertEquals(13, count("source_discovery_candidates"));
    }

    @Test
    void aggregatorLeadStaysVisibleWithoutFetchingItsHomepage() {
        var fetches = new AtomicInteger();
        Clock clock = Clock.systemUTC();
        var result = discovery(query -> response("https://wellfound.com/jobs/1",
                        "https://en.devjobs.at/jobs/2", "https://www.greenhouse.com/",
                        "https://tavily.com/", "https://job-boards.greenhouse.io/", CAREERS),
                verifier(uri -> {
                    fetches.incrementAndGet();
                    return uri.getPath().equals("/") ? "<a href='/careers'>Careers</a>"
                            : "<a href='" + ATS + "'>Jobs</a>";
                }, clock), clock).run();
        assertEquals(1, result.activated());
        assertEquals(2, fetches.get());
        assertEquals("AGGREGATOR_ONLY_LEAD", jdbc.queryForObject("SELECT verification_reason "
                + "FROM source_discovery_candidates WHERE candidate_url LIKE '%wellfound.com%'", String.class));
        assertEquals("AGGREGATOR_ONLY_LEAD", jdbc.queryForObject("SELECT verification_reason "
                + "FROM source_discovery_candidates WHERE candidate_url LIKE '%devjobs.at%'", String.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM source_discovery_candidates "
                + "WHERE lead_kind = 'NON_EMPLOYER' AND verification_reason = 'NON_EMPLOYER_SITE'",
                Integer.class));
    }

    @Test
    void httpFailuresHaveBoundedSafeDiagnosticsAndPermanentFailuresDoNotRetry() {
        Logger logger = (Logger) LoggerFactory.getLogger(SourceVerificationService.class);
        var output = new ListAppender<ILoggingEvent>();
        output.start();
        logger.addAppender(output);
        try {
            Clock clock = Clock.systemUTC();
            var fetcher = new SafeHtmlFetcher((uri, remaining) -> new SafeHtmlFetcher.PageResponse(
                    404, "text/html", null, "identity", new byte[0]));
            var discovery = discovery(query -> response("https://acme.example/jobs?token=synthetic-private"),
                    new SourceVerificationService(fetcher, new SourceRegistryRepository(jdbc), clock, manager), clock);
            assertEquals(1, discovery.run().review());
            assertEquals(0, discovery.run().review());
            assertEquals("HTTP_PERMANENT", jdbc.queryForObject("SELECT verification_reason "
                    + "FROM source_discovery_candidates", String.class));
            assertEquals(1, jdbc.queryForObject("SELECT verification_attempts "
                    + "FROM source_discovery_candidates", Integer.class));
            String diagnostic = output.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.startsWith("Discovery verification:")).findFirst().orElseThrow();
            assertTrue(diagnostic.contains("stage=HOMEPAGE"));
            assertTrue(diagnostic.contains("host=acme.example"));
            assertTrue(diagnostic.contains("http_status=404"));
            assertTrue(diagnostic.contains("redirects=0"));
            assertTrue(diagnostic.contains("retryable=false"));
            assertTrue(diagnostic.contains("requests=1"));
            assertFalse(diagnostic.contains("synthetic-private"));
            assertFalse(diagnostic.contains("https://"));
        } finally {
            logger.detachAppender(output);
            output.stop();
        }
    }

    @Test
    void rateLimitedHomepageRetriesOnlyWhenDueAndLinkLimitReportsActualCount() {
        Clock clock = Clock.systemUTC();
        var calls = new AtomicInteger();
        var fetcher = new SafeHtmlFetcher((uri, remaining) -> {
            if (calls.getAndIncrement() == 0) {
                return new SafeHtmlFetcher.PageResponse(429, "text/html", null, "identity", new byte[0]);
            }
            String html = "<a href='/careers'>Careers</a>".repeat(201);
            return new SafeHtmlFetcher.PageResponse(200, "text/html", null, "identity",
                    html.getBytes(StandardCharsets.UTF_8));
        });
        Logger logger = (Logger) LoggerFactory.getLogger(SourceVerificationService.class);
        var output = new ListAppender<ILoggingEvent>();
        output.start();
        logger.addAppender(output);
        try {
            var discovery = discovery(query -> response(CAREERS),
                    new SourceVerificationService(fetcher, new SourceRegistryRepository(jdbc), clock, manager), clock);
            assertEquals(1, discovery.run().retryable());
            assertEquals(0, discovery.run().review());
            assertEquals(1, calls.get());
            jdbc.update("UPDATE source_discovery_candidates SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
            assertEquals(1, discovery.run().review());
            assertEquals(2, calls.get());
            assertEquals("TOO_MANY_LINKS", jdbc.queryForObject("SELECT verification_reason "
                    + "FROM source_discovery_candidates", String.class));
            String diagnostic = output.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains("reason=TOO_MANY_LINKS")).findFirst().orElseThrow();
            assertTrue(diagnostic.contains("links=201"));
            assertTrue(diagnostic.contains("link_limit=200"));
            assertTrue(output.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(line -> line.contains("http_status=429") && line.contains("retryable=true")));
        } finally {
            logger.detachAppender(output);
            output.stop();
        }
    }

    @Test
    void officialHomepageToCareersToAtsActivatesImportsAndReplaysWithoutDuplicates() {
        AtomicInteger pages = new AtomicInteger();
        Clock clock = Clock.systemUTC();
        var verification = verifier(uri -> {
            pages.incrementAndGet();
            return uri.getPath().equals("/") ? "<a href='/careers'>Careers</a>"
                    : "<a href='" + ATS + "'>Open roles</a>";
        }, clock);
        var discovery = discovery(verification, clock);
        var first = discovery.run();
        assertEquals(4, first.attempts());
        assertEquals(1, first.leads());
        assertEquals(1, first.confirmed());
        assertEquals(1, first.activated());
        assertEquals(0, first.review());
        assertEquals(2, pages.get());
        var registry = registry(clock);
        assertEquals(1, registry.importActive().created());
        assertEquals("CANDIDATE", jdbc.queryForObject(
                "SELECT decision FROM vacancy_candidate_assessments", String.class));
        assertEquals(1, count("verified_source_registry"));
        assertEquals(1, count("vacancies"));

        var replay = discovery.run();
        assertEquals(1, replay.leads());
        assertEquals(0, replay.confirmed());
        assertEquals(0, replay.activated());
        assertEquals(2, pages.get());
        assertEquals(1, registry.importActive().unchanged());
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("source_discovery_candidates"));
    }

    @Test
    void individualEmployerVacancyUsesHomepageCareersEvidence() {
        Clock clock = Clock.systemUTC();
        AtomicInteger pages = new AtomicInteger();
        var verifier = verifier(uri -> {
            pages.incrementAndGet();
            return uri.getPath().equals("/") ? "<a href='/company/work'>Careers</a>"
                    : "<a href='" + ATS + "'>Apply</a>";
        }, clock);
        var result = discovery(query -> response("https://acme.example/roles/42"), verifier, clock).run();
        assertEquals(1, result.activated());
        assertEquals(2, pages.get());
        assertEquals(1, registry(clock).importActive().created());
        assertEquals("https://acme.example/company/work", jdbc.queryForObject(
                "SELECT official_page_url FROM verified_source_registry", String.class));
    }

    @Test
    void sameHostCareersRedirectStillEstablishesBoardAssociation() {
        Clock clock = Clock.systemUTC();
        AtomicInteger pages = new AtomicInteger();
        var fetcher = new SafeHtmlFetcher((uri, remaining) -> {
            pages.incrementAndGet();
            if (uri.getPath().equals("/careers")) {
                return new SafeHtmlFetcher.PageResponse(302, null, "/en/roles", "identity", new byte[0]);
            }
            String html = uri.getPath().equals("/") ? "<a href='/careers'>Careers</a>"
                    : "<a href='" + ATS + "'>Jobs</a>";
            return new SafeHtmlFetcher.PageResponse(200, "text/html", null, "identity",
                    html.getBytes(StandardCharsets.UTF_8));
        });
        var verifier = new SourceVerificationService(fetcher, new SourceRegistryRepository(jdbc), clock, manager);
        assertEquals(1, discovery(query -> response("https://acme.example/openings/42"), verifier, clock)
                .run().activated());
        assertEquals(3, pages.get());
        assertEquals("https://acme.example/en/roles", jdbc.queryForObject(
                "SELECT official_page_url FROM verified_source_registry", String.class));
    }

    @Test
    void employerCareersRedirectToExactAtsBoardIsAssociationEvidence() {
        Clock clock = Clock.systemUTC();
        var fetcher = new SafeHtmlFetcher((uri, remaining) -> {
            if (uri.getPath().equals("/careers")) {
                return new SafeHtmlFetcher.PageResponse(302, null, ATS, "identity", new byte[0]);
            }
            return new SafeHtmlFetcher.PageResponse(200, "text/html", null, "identity",
                    "<a href='/careers'>Careers</a>".getBytes(StandardCharsets.UTF_8));
        });
        var verifier = new SourceVerificationService(fetcher, new SourceRegistryRepository(jdbc), clock, manager);
        assertEquals(1, discovery(query -> response(CAREERS), verifier, clock).run().activated());
        assertEquals(1, count("verified_source_registry"));
        assertEquals(ATS, jdbc.queryForObject("SELECT evidence_url FROM verified_source_registry", String.class));
    }

    @Test
    void directAtsVacancyFindsIndependentEmployerAndSharesLookupAcrossDuplicateBoardLeads() {
        Clock clock = Clock.systemUTC();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger pages = new AtomicInteger();
        SearchGateway gateway = query -> {
            calls.incrementAndGet();
            if (query.query().startsWith("job-boards.greenhouse.io/")) {
                assertEquals(3, query.maxResults());
                return response("https://acme.example/opening/42");
            }
            return response(ATS, "https://job-boards.greenhouse.io/acme");
        };
        var verifier = verifier(uri -> {
            pages.incrementAndGet();
            return uri.getPath().equals("/") ? "<a href='/careers'>Careers</a>"
                    : "<a href='" + ATS + "'>Jobs</a>";
        }, clock);
        var result = discovery(gateway, verifier, clock).run();
        assertEquals(5, result.attempts());
        assertEquals(5, calls.get());
        assertEquals(2, pages.get());
        assertEquals(2, result.confirmed());
        assertEquals(1, result.activated());
        assertEquals(2, count("source_discovery_candidates"));
        assertEquals(1, count("verified_source_registry"));
        assertEquals(1, registry(clock).importActive().created());
    }

    @Test
    void directAtsWithoutIndependentEvidenceStaysInactive() {
        Clock clock = Clock.systemUTC();
        AtomicInteger fetches = new AtomicInteger();
        SearchGateway gateway = query -> query.query().startsWith("job-boards.greenhouse.io/")
                ? new SearchResponseDTO(List.of(), "synthetic-request", 1) : response(ATS);
        var result = discovery(gateway, verifier(uri -> {
            fetches.incrementAndGet();
            return "";
        }, clock), clock).run();
        assertEquals(5, result.attempts());
        assertEquals(1, result.review());
        assertEquals(0, result.activated());
        assertEquals(0, fetches.get());
        assertEquals("NO_INDEPENDENT_EMPLOYER_EVIDENCE", jdbc.queryForObject(
                "SELECT verification_reason FROM source_discovery_candidates", String.class));
    }

    @Test
    void conflictingBoardsOnEmployerCareersPagesRequireReview() {
        Clock clock = Clock.systemUTC();
        var verifier = verifier(uri -> switch (uri.getPath()) {
            case "/" -> "<a href='/careers'>Careers</a><a href='/jobs'>Jobs</a>";
            case "/careers" -> "<a href='" + ATS + "'>Greenhouse</a>";
            default -> "<a href='https://jobs.lever.co/other/42'>Lever</a>";
        }, clock);
        var result = discovery(verifier, clock).run();
        assertEquals(1, result.review());
        assertEquals(0, result.activated());
        assertEquals("AMBIGUOUS_LINKS", jdbc.queryForObject(
                "SELECT verification_reason FROM source_discovery_candidates", String.class));
        assertEquals(0, count("verified_source_registry"));
    }

    @Test
    void directAtsWithTwoClaimingEmployerDomainsRequiresReview() {
        Clock clock = Clock.systemUTC();
        SearchGateway gateway = query -> query.query().startsWith("job-boards.greenhouse.io/")
                ? response("https://acme.example/careers", "https://other.example/careers")
                : response(ATS);
        var verifier = verifier(uri -> uri.getPath().equals("/")
                ? "<a href='/careers'>Careers</a>" : "<a href='" + ATS + "'>Jobs</a>", clock);
        var result = discovery(gateway, verifier, clock).run();
        assertEquals(1, result.review());
        assertEquals(0, result.activated());
        assertEquals("CONFLICTING_EMPLOYER_EVIDENCE", jdbc.queryForObject(
                "SELECT verification_reason FROM source_discovery_candidates", String.class));
        assertEquals(0, count("verified_source_registry"));
    }

    @Test
    void exhaustedInvocationCannotSendAnotherBoardLookup() {
        Clock clock = Clock.systemUTC();
        AtomicInteger calls = new AtomicInteger();
        SearchGateway gateway = query -> {
            int call = calls.incrementAndGet();
            if (call == 3) {
                return response(ATS);
            }
            throw new SearchFailureException(SearchFailureException.Kind.TRANSIENT, null, null);
        };
        var result = discovery(gateway, verifier(uri -> fail("No employer HTTP expected"), clock), clock).run();
        assertEquals(12, calls.get());
        assertEquals(12, result.attempts());
        assertEquals(1, result.retryable());
        assertEquals("INVOCATION_SEARCH_LIMIT", jdbc.queryForObject(
                "SELECT verification_reason FROM source_discovery_candidates", String.class));
    }

    @Test
    void missingHomepageEvidenceRemainsInactiveAndDoesNotFetchBoard() {
        AtomicInteger pages = new AtomicInteger();
        Clock clock = Clock.systemUTC();
        var discovery = discovery(verifier(uri -> {
            pages.incrementAndGet();
            return "<a href='/unrelated'>Unrelated</a>";
        }, clock), clock);
        var result = discovery.run();
        assertEquals(1, result.review());
        assertEquals(0, result.activated());
        assertEquals(1, pages.get());
        assertEquals("NO_HOMEPAGE_CAREERS_LINK", jdbc.queryForObject(
                "SELECT verification_reason FROM source_discovery_candidates", String.class));
        assertEquals(0, count("verified_source_registry"));
        assertEquals(0, registry(clock).importActive().sources());
    }

    @Test
    void disabledOrStaleBillingGateSendsNoSearchRequestAndClaimsNoLead() {
        AtomicInteger requests = new AtomicInteger();
        Clock clock = Clock.fixed(java.time.Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);
        var searchRepository = new SearchPilotRepository(jdbc);
        var search = new SearchPilotService(query -> {
            requests.incrementAndGet();
            return new SearchResponseDTO(List.of(), "synthetic-request", 1);
        }, searchRepository, new SearchAttemptReservationService(searchRepository, manager), clock);
        var verifier = verifier(uri -> "<html></html>", clock);
        var registry = new SourceRegistryRepository(jdbc);
        var disabled = new SourceDiscoveryService(search, verifier, registry, clock, manager, false, "");
        assertEquals(0, disabled.run().attempts());
        var stale = new SourceDiscoveryService(search, verifier, registry, clock, manager,
                true, "2026-10-01");
        assertThrows(IllegalStateException.class, stale::run);
        assertEquals(0, requests.get());
        assertEquals(0, count("search_pilot_attempts"));
    }

    @Test
    void failedHomepageFetchRetriesOnlyAfterDueTimeAndRetainsIdentity() {
        AtomicInteger fetches = new AtomicInteger();
        Clock clock = Clock.systemUTC();
        var fetcher = new SafeHtmlFetcher((uri, remaining) -> {
            if (fetches.getAndIncrement() == 0) {
                throw new java.io.IOException("synthetic fetch failure");
            }
            String html = uri.getPath().equals("/") ? "<a href='/careers'>Careers</a>"
                    : "<a href='" + ATS + "'>Jobs</a>";
            return new SafeHtmlFetcher.PageResponse(200, "text/html", null, "identity",
                    html.getBytes(StandardCharsets.UTF_8));
        });
        var discovery = discovery(new SourceVerificationService(fetcher, new SourceRegistryRepository(jdbc),
                clock, manager), clock);
        assertEquals(1, discovery.run().retryable());
        assertEquals(0, count("verified_source_registry"));
        assertEquals(0, discovery.run().confirmed());
        assertEquals(1, fetches.get());
        jdbc.update("UPDATE source_discovery_candidates SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        assertEquals(1, discovery.run().activated());
        assertEquals(3, fetches.get());
        assertEquals(1, count("source_discovery_candidates"));
        assertEquals(2, jdbc.queryForObject("SELECT verification_attempts FROM source_discovery_candidates",
                Integer.class));
    }

    @Test
    void thirdFetchFailureIsReportedAsReviewRatherThanAnotherRetry() {
        AtomicInteger fetches = new AtomicInteger();
        Clock clock = Clock.systemUTC();
        var fetcher = new SafeHtmlFetcher((uri, remaining) -> {
            fetches.incrementAndGet();
            throw new java.io.IOException("synthetic fetch failure");
        });
        var discovery = discovery(new SourceVerificationService(fetcher, new SourceRegistryRepository(jdbc),
                clock, manager), clock);
        for (int attempt = 1; attempt <= 3; attempt++) {
            var result = discovery.run();
            assertEquals(attempt == 3 ? 1 : 0, result.review());
            assertEquals(attempt == 3 ? 0 : 1, result.retryable());
            jdbc.update("UPDATE source_discovery_candidates SET next_attempt_at = "
                    + "CURRENT_TIMESTAMP - INTERVAL '1 second'");
        }
        assertEquals(0, discovery.run().review());
        assertEquals(3, fetches.get());
        assertEquals("RETRY_EXHAUSTED", jdbc.queryForObject(
                "SELECT verification_reason FROM source_discovery_candidates", String.class));
    }

    @Test
    void expiredClaimsExhaustAndStaleWorkerCannotActivate() {
        Clock clock = Clock.systemUTC();
        var repository = new SourceRegistryRepository(jdbc);
        discovery(verifier(uri -> uri.getPath().equals("/") ? "<a href='/careers'>Careers</a>"
                : "<a href='" + ATS + "'>Jobs</a>", clock), clock).run();
        // The successful row is not claimable; create a second unverified lead from the same search attempt.
        long attempt = jdbc.queryForObject("SELECT id FROM search_pilot_attempts ORDER BY id LIMIT 1", Long.class);
        new SearchPilotRepository(jdbc).recordCandidate(attempt, "https://other.example/careers", "EMPLOYER", 1);
        com.alexs.jobagent.source.dto.DiscoveryClaimDTO stale = null;
        for (int i = 0; i < 3; i++) {
            var claims = new org.springframework.transaction.support.TransactionTemplate(manager)
                    .execute(status -> repository.claimDiscoveryBatch(clock.instant(), 1));
            assertEquals(1, claims.size());
            if (stale == null) {
                stale = claims.getFirst();
            }
            jdbc.update("UPDATE source_discovery_candidates SET claimed_until = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                    + "WHERE id = ?", claims.getFirst().id());
        }
        var noFourth = new org.springframework.transaction.support.TransactionTemplate(manager)
                .execute(status -> repository.claimDiscoveryBatch(clock.instant(), 1));
        assertTrue(noFourth.isEmpty());
        assertEquals("RETRY_EXHAUSTED", jdbc.queryForObject("SELECT verification_reason "
                + "FROM source_discovery_candidates WHERE id = ?", String.class, stale.id()));
        var verifier = verifier(uri -> "<a href='/careers'>Careers</a>", clock);
        var oldClaim = stale;
        assertThrows(IllegalStateException.class, () -> verifier.verifyAutomatic(oldClaim));
        assertEquals(1, count("verified_source_registry"));
    }

    private SourceDiscoveryService discovery(SourceVerificationService verifier, Clock clock) {
        return discovery(query -> response(CAREERS), verifier, clock);
    }

    private SourceDiscoveryService discovery(SearchGateway gateway, SourceVerificationService verifier, Clock clock) {
        var searchRepository = new SearchPilotRepository(jdbc);
        var search = new SearchPilotService(gateway, searchRepository,
                new SearchAttemptReservationService(searchRepository, manager), clock, delay -> { });
        return new SourceDiscoveryService(search, verifier, new SourceRegistryRepository(jdbc),
                clock, manager, true, LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString());
    }

    private static SearchResponseDTO response(String... urls) {
        List<SearchResultDTO> results = java.util.stream.IntStream.range(0, urls.length)
                .mapToObj(index -> new SearchResultDTO(urls[index], "Synthetic result", null, index + 1))
                .toList();
        return new SearchResponseDTO(results, "synthetic-request", 1);
    }

    private SourceVerificationService verifier(java.util.function.Function<java.net.URI, String> pages,
                                               Clock clock) {
        var fetcher = new SafeHtmlFetcher((uri, remaining) -> new SafeHtmlFetcher.PageResponse(
                200, "text/html", null, "identity", pages.apply(uri).getBytes(StandardCharsets.UTF_8)));
        return new SourceVerificationService(fetcher, new SourceRegistryRepository(jdbc), clock, manager);
    }

    private SourceRegistryService registry(Clock clock) {
        var batch = new GreenhouseBatchService(board -> new GreenhouseFetchResultDTO(List.of(
                new GreenhouseListingDTO("42", "Software Engineer", "Berlin",
                        "<p>Build Java backend services and REST APIs.</p>", ATS,
                        clock.instant(), json.readTree("{\"id\":42,\"title\":\"Software Engineer\"}"))), 1),
                greenhouseIngestion, clock);
        return new SourceRegistryService(new SourceRegistryRepository(jdbc), batch, lever,
                assessments, clock, manager);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
