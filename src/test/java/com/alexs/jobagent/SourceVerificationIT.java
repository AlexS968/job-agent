package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.source.dto.GreenhouseFetchResultDTO;
import com.alexs.jobagent.source.dto.GreenhouseListingDTO;
import com.alexs.jobagent.source.dto.LeverFetchResultDTO;
import com.alexs.jobagent.source.dto.LeverListingDTO;
import com.alexs.jobagent.source.dto.SourceCandidateDTO;
import com.alexs.jobagent.source.dto.SourceVerificationDTO;
import com.alexs.jobagent.source.repository.SourceRegistryRepository;
import com.alexs.jobagent.source.service.GreenhouseBatchService;
import com.alexs.jobagent.source.service.GreenhouseIngestionService;
import com.alexs.jobagent.source.service.LeverBatchService;
import com.alexs.jobagent.source.service.LeverIngestionService;
import com.alexs.jobagent.source.service.SafeHtmlFetcher;
import com.alexs.jobagent.source.service.SourceRegistryService;
import com.alexs.jobagent.source.service.SourceVerificationService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class SourceVerificationIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("job_agent_verification_test").withUsername("test_user")
            .withPassword("synthetic_test_secret_123");
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final String OFFICIAL = "https://acme.example/careers";
    private static final String ATS = "https://job-boards.greenhouse.io/acme/jobs/1";
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
    @Autowired LeverIngestionService leverIngestion;
    @Autowired JsonMapper json;

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE source_verification_attempts, source_verification_decisions, "
                + "verified_source_registry RESTART IDENTITY");
        jdbc.execute("TRUNCATE vacancy_sources, source_items, vacancies, employers, agent_runs RESTART IDENTITY CASCADE");
    }

    @Test
    void verifiedAssociationIsIdempotentAndImportUsesExistingIngestion() throws Exception {
        var calls = new AtomicInteger();
        SourceVerificationService verification = verifier("<a href='" + ATS + "'>Jobs</a>", NOW, calls);
        SourceCandidateDTO candidate = trusted(ATS);
        assertEquals("VERIFIED", verification.verify(List.of(candidate)).getFirst().outcome());
        assertEquals("VERIFIED", verification.verify(List.of(candidate)).getFirst().outcome());
        assertEquals(2, calls.get());
        assertEquals(1, count("source_verification_attempts"));
        assertEquals(1, count("verified_source_registry"));
        assertEquals(NOW, jdbc.queryForObject("SELECT observed_at FROM source_verification_decisions",
                java.sql.Timestamp.class).toInstant());
        var batches = new AtomicInteger();
        var listing = new GreenhouseListingDTO("1", "Synthetic Engineer", "Remote", "<p>Role</p>", ATS,
                NOW.minusSeconds(3600), json.readTree("{\"id\":1,\"title\":\"Synthetic Engineer\"}"));
        GreenhouseBatchService batch = new GreenhouseBatchService(board -> {
            batches.incrementAndGet();
            assertEquals("acme", board.token());
            return new GreenhouseFetchResultDTO(List.of(listing), 1);
        }, greenhouseIngestion, clock(NOW));
        SourceRegistryService registry = new SourceRegistryService(new SourceRegistryRepository(jdbc), batch,
                lever, clock(NOW), manager);
        assertEquals(0, registry.importActive().sources());
        assertTrue(registry.activate(1));
        assertEquals(1, registry.importActive().sources());
        assertEquals(1, registry.importActive().sources());
        assertEquals(2, batches.get());
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("outbox_events"));
    }

    @Test
    void atsWithoutTrustRootStaysManualWithoutHttpOrActivation() {
        var calls = new AtomicInteger();
        var result = verifier("<a href='" + ATS + "'>Jobs</a>", NOW, calls).verify(List.of(
                new SourceCandidateDTO(ATS, "manual-candidate", null, null, null))).getFirst();
        assertEquals("MANUAL_REVIEW", result.outcome());
        assertEquals("NO_TRUST_ROOT", result.reason());
        assertEquals(0, calls.get());
        assertEquals(0, count("verified_source_registry"));
        assertEquals(1, count("source_verification_attempts"));
    }

    @Test
    void independentlyReviewedOfficialDomainCanEstablishAssociation() {
        SourceCandidateDTO candidate = new SourceCandidateDTO(ATS, "independently-reviewed",
                "acme.example", OFFICIAL, "Acme Synthetic");
        var result = verifier("<a href='" + ATS + "'>Jobs</a>", NOW, new AtomicInteger())
                .verify(List.of(candidate)).getFirst();
        assertEquals("VERIFIED", result.outcome());
        assertEquals("independently-reviewed", result.origin());
        assertFalse(registry(NOW).sources().getFirst().active());
    }

    @Test
    void euLeverAssociationImportsThroughExistingLeverAdapter() throws Exception {
        String leverUrl = "https://jobs.eu.lever.co/acme/1";
        var verification = verifier("<a href='" + leverUrl + "'>Jobs</a>", NOW, new AtomicInteger());
        assertEquals("VERIFIED", verification.verify(List.of(trusted(leverUrl))).getFirst().outcome());
        var listing = new LeverListingDTO("1", "Synthetic Engineer", "Remote", "remote",
                "<p>Role</p>", leverUrl, leverUrl + "/apply",
                json.readTree("{\"id\":\"1\",\"text\":\"Synthetic Engineer\"}"));
        var calls = new AtomicInteger();
        LeverBatchService batch = new LeverBatchService(site -> {
            calls.incrementAndGet();
            assertEquals("eu", site.region());
            assertEquals("acme", site.site());
            return new LeverFetchResultDTO(List.of(listing), 1, NOW);
        }, leverIngestion);
        var registry = new SourceRegistryService(new SourceRegistryRepository(jdbc),
                new GreenhouseBatchService(board -> new GreenhouseFetchResultDTO(List.of(), 1),
                        greenhouseIngestion, clock(NOW)), batch, clock(NOW), manager);
        assertTrue(registry.activate(1));
        assertEquals(0, registry.importActive().failures());
        assertEquals(1, calls.get());
        assertEquals("lever:eu:acme", jdbc.queryForObject("SELECT source_namespace FROM vacancies", String.class));
    }

    @Test
    void ambiguousAndUnsupportedOfficialPagesAreNotPromoted() {
        var two = verifier("<a href='" + ATS + "'>One</a>"
                + "<a href='https://jobs.eu.lever.co/other'>Two</a>", NOW, new AtomicInteger());
        assertEquals("AMBIGUOUS_LINKS", two.verify(List.of(trusted(OFFICIAL))).getFirst().reason());
        var unsupported = verifier("<a href='https://jobs.smartrecruiters.com/acme'>Jobs</a>",
                NOW.plusSeconds(1), new AtomicInteger());
        SourceCandidateDTO other = new SourceCandidateDTO("https://other.example/careers",
                "owner-confirmed", "other.example", "https://other.example/careers", "Other Synthetic Employer");
        assertEquals("UNSUPPORTED", unsupported.verify(List.of(other)).getFirst().outcome());
        assertEquals(0, count("verified_source_registry"));
    }

    @Test
    void externalBaseAndScriptOnlyLinksDoNotEstablishOwnership() {
        var base = verifier("<base href='https://jobs.lever.co/acme/'>"
                + "<a href='/acme/1'>Jobs</a>", NOW, new AtomicInteger());
        assertEquals("NO_SUPPORTED_LINK", base.verify(List.of(trusted(OFFICIAL))).getFirst().reason());
        var script = verifier("<script>location.href='" + ATS + "'</script>",
                NOW.plusSeconds(1), new AtomicInteger());
        assertEquals("NO_SUPPORTED_LINK", script.verify(List.of(trusted(OFFICIAL))).getFirst().reason());
        assertEquals(0, count("verified_source_registry"));
    }

    @Test
    void conflictingEmployerDeactivatesWithoutTransferringIdentity() {
        verifier("<a href='" + ATS + "'>Jobs</a>", NOW, new AtomicInteger()).verify(List.of(trusted(ATS)));
        SourceRegistryService registry = registry(NOW);
        assertTrue(registry.activate(1));
        SourceCandidateDTO another = new SourceCandidateDTO(ATS, "owner-confirmed", "other.example",
                "https://other.example/careers", "Other Synthetic Employer");
        var conflict = verifier("<a href='" + ATS + "'>Jobs</a>", NOW.plusSeconds(1), new AtomicInteger());
        assertEquals("MANUAL_REVIEW", conflict.verify(List.of(another)).getFirst().outcome());
        var source = registry.sources().getFirst();
        assertEquals("acme.example", source.employerDomain());
        assertEquals("MANUAL_REVIEW", source.status());
        assertFalse(source.active());
        assertFalse(registry.activate(source.id()));
        assertFalse(registry(NOW.plusSeconds(31L * 86400)).activate(source.id()));
        assertEquals("MANUAL_REVIEW", registry.sources().getFirst().status());
        assertEquals("MANUAL_REVIEW", verifier("<a href='" + ATS + "'>Jobs</a>",
                NOW.plusSeconds(32L * 86400), new AtomicInteger()).verify(List.of(trusted(ATS))).getFirst().outcome());
        assertEquals("MANUAL_REVIEW", registry.sources().getFirst().status());
    }

    @Test
    void laterBoardChangeOnSameOfficialDomainRequiresManualReview() {
        verifier("<a href='" + ATS + "'>Jobs</a>", NOW, new AtomicInteger()).verify(List.of(trusted(ATS)));
        assertTrue(registry(NOW).activate(1));
        String replacement = "https://jobs.lever.co/acme/1";
        var result = verifier("<a href='" + replacement + "'>Jobs</a>", NOW.plusSeconds(1),
                new AtomicInteger()).verify(List.of(trusted(replacement))).getFirst();
        assertEquals("MANUAL_REVIEW", result.outcome());
        assertEquals("IDENTITY_CHANGE_CONFLICT", result.reason());
        assertEquals(1, count("verified_source_registry"));
        assertEquals("MANUAL_REVIEW", registry(NOW.plusSeconds(1)).sources().getFirst().status());
        assertFalse(registry(NOW.plusSeconds(1)).sources().getFirst().active());
        assertEquals("CONFLICT", jdbc.queryForObject(
                "SELECT decision FROM source_verification_decisions", String.class));
    }

    @Test
    void equalTimeDifferentEvidenceRequiresReviewInsteadOfArrivalOrder() {
        var service = verifier("<a href='" + ATS + "'>Jobs</a>", NOW, new AtomicInteger());
        service.verify(List.of(trusted(ATS)));
        assertTrue(registry(NOW).activate(1));
        var second = service.verify(List.of(trusted("https://job-boards.greenhouse.io/acme/jobs/2"))).getFirst();
        assertEquals("MANUAL_REVIEW", second.outcome());
        assertEquals("EQUAL_TIME_CONFLICT", second.reason());
        assertFalse(registry(NOW).sources().getFirst().active());
    }

    @Test
    void subMicrosecondCollisionCannotResolveConflictingEvidenceByArrival() {
        verifier("<a href='" + ATS + "'>Jobs</a>", NOW.plusNanos(100),
                new AtomicInteger()).verify(List.of(trusted(ATS)));
        assertTrue(registry(NOW).activate(1));
        var negative = verifier("<p>No ATS link</p>", NOW.plusNanos(900),
                new AtomicInteger()).verify(List.of(trusted(ATS))).getFirst();
        assertEquals("EQUAL_TIME_CONFLICT", negative.reason());
        assertEquals("CONFLICT", jdbc.queryForObject(
                "SELECT decision FROM source_verification_decisions", String.class));
        assertEquals("MANUAL_REVIEW", registry(NOW).sources().getFirst().status());
        assertFalse(registry(NOW).sources().getFirst().active());
    }

    @Test
    void staleFailureCannotOverwriteNewerActivationAndFreshnessExpires() {
        verifier("<a href='" + ATS + "'>Jobs</a>", NOW, new AtomicInteger()).verify(List.of(trusted(ATS)));
        assertTrue(registry(NOW).activate(1));
        verifier("<p>No ATS link</p>", NOW.minusSeconds(1), new AtomicInteger()).verify(List.of(trusted(ATS)));
        assertTrue(registry(NOW).sources().getFirst().active());
        assertEquals(1, registry(NOW.plusSeconds(31L * 86400)).importActive().failures());
        assertEquals("STALE", registry(NOW).sources().getFirst().status());
    }

    @Test
    void expiredSourceRecoversAfterFreshEvidenceButRequiresExplicitReactivation() {
        verifier("<a href='" + ATS + "'>Jobs</a>", NOW, new AtomicInteger()).verify(List.of(trusted(ATS)));
        assertTrue(registry(NOW).activate(1));
        Instant later = NOW.plusSeconds(31L * 86400);
        assertEquals(1, registry(later).importActive().failures());
        assertEquals("STALE", registry(later).sources().getFirst().status());
        assertEquals("VERIFIED", verifier("<a href='" + ATS + "'>Jobs</a>", later,
                new AtomicInteger()).verify(List.of(trusted(ATS))).getFirst().outcome());
        var recovered = registry(later).sources().getFirst();
        assertEquals("VERIFIED", recovered.status());
        assertFalse(recovered.active());
        assertEquals(0, registry(later).importActive().sources());
        assertTrue(registry(later).activate(recovered.id()));
        assertEquals(1, registry(later).importActive().sources());
        assertEquals(1, count("source_verification_attempts"));
        assertEquals(later, jdbc.queryForObject("SELECT observed_at FROM source_verification_decisions",
                java.sql.Timestamp.class).toInstant());
    }

    @Test
    void newerNegativeDecisionPreventsOlderPositiveFromCreatingFirstSource() throws Exception {
        CountDownLatch oldFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseOldFetch = new CountDownLatch(1);
        var old = blockingVerifier(NOW, oldFetchStarted, releaseOldFetch);
        CompletableFuture<SourceVerificationDTO> oldRun = CompletableFuture.supplyAsync(() ->
                old.verify(List.of(trusted(ATS))).getFirst());
        try {
            assertTrue(oldFetchStarted.await(10, TimeUnit.SECONDS));
            assertEquals("MANUAL_REVIEW", verifier("<p>No ATS link</p>", NOW.plusSeconds(1),
                    new AtomicInteger()).verify(List.of(trusted(ATS))).getFirst().outcome());
        } finally {
            releaseOldFetch.countDown();
        }
        assertEquals("SUPERSEDED_OBSERVATION", oldRun.get(10, TimeUnit.SECONDS).reason());
        assertEquals(0, count("verified_source_registry"));
        assertEquals("UNVERIFIED", jdbc.queryForObject(
                "SELECT decision FROM source_verification_decisions", String.class));
        assertEquals("VERIFIED", verifier("<a href='" + ATS + "'>Jobs</a>", NOW.plusSeconds(2),
                new AtomicInteger()).verify(List.of(trusted(ATS))).getFirst().outcome());
        assertFalse(registry(NOW.plusSeconds(2)).sources().getFirst().active());
    }

    @Test
    void newerNegativeDecisionDeactivatesExistingSourceBeforeOldPositiveFinishes() throws Exception {
        verifier("<a href='" + ATS + "'>Jobs</a>", NOW.minusSeconds(1),
                new AtomicInteger()).verify(List.of(trusted(ATS)));
        assertTrue(registry(NOW).activate(1));
        CountDownLatch oldFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseOldFetch = new CountDownLatch(1);
        var old = blockingVerifier(NOW, oldFetchStarted, releaseOldFetch);
        CompletableFuture<SourceVerificationDTO> oldRun = CompletableFuture.supplyAsync(() ->
                old.verify(List.of(trusted(ATS))).getFirst());
        try {
            assertTrue(oldFetchStarted.await(10, TimeUnit.SECONDS));
            verifier("<p>No ATS link</p>", NOW.plusSeconds(1),
                    new AtomicInteger()).verify(List.of(trusted(ATS)));
        } finally {
            releaseOldFetch.countDown();
        }
        assertEquals("SUPERSEDED_OBSERVATION", oldRun.get(10, TimeUnit.SECONDS).reason());
        var source = registry(NOW.plusSeconds(1)).sources().getFirst();
        assertEquals("UNVERIFIED", source.status());
        assertFalse(source.active());
        assertFalse(registry(NOW.plusSeconds(1)).activate(source.id()));
        assertEquals("VERIFIED", verifier("<a href='" + ATS + "'>Jobs</a>", NOW.plusSeconds(2),
                new AtomicInteger()).verify(List.of(trusted(ATS))).getFirst().outcome());
        assertFalse(registry(NOW.plusSeconds(2)).sources().getFirst().active());
    }

    @Test
    void untrustedClaimCannotDeactivateAlreadyVerifiedSource() {
        verifier("<a href='" + ATS + "'>Jobs</a>", NOW, new AtomicInteger()).verify(List.of(trusted(ATS)));
        assertTrue(registry(NOW).activate(1));
        SourceCandidateDTO untrusted = new SourceCandidateDTO(ATS, "manual-candidate",
                "acme.example", OFFICIAL, "Acme Synthetic");
        assertEquals("NO_TRUST_ROOT", verifier("<p>No ATS link</p>", NOW.plusSeconds(1),
                new AtomicInteger()).verify(List.of(untrusted)).getFirst().reason());
        assertTrue(registry(NOW).sources().getFirst().active());
    }

    @Test
    void concurrentVerificationDeduplicatesAndFailedRegistryWriteRollsBackEvidence() {
        var service = verifier("<a href='" + ATS + "'>Jobs</a>", NOW, new AtomicInteger());
        var jobs = List.of(CompletableFuture.runAsync(() -> service.verify(List.of(trusted(ATS)))),
                CompletableFuture.runAsync(() -> service.verify(List.of(trusted(ATS)))));
        jobs.forEach(CompletableFuture::join);
        assertEquals(1, count("source_verification_attempts"));
        assertEquals(1, count("verified_source_registry"));
        assertEquals(1, count("source_verification_decisions"));
        SourceRegistryRepository broken = new SourceRegistryRepository(jdbc) {
            @Override public void saveAttempt(SourceVerificationDTO result) {
                super.saveAttempt(result);
                throw new IllegalStateException("synthetic repository failure");
            }
        };
        var rollback = new SourceVerificationService(fetcher("<a href='https://jobs.lever.co/newsite'>Jobs</a>",
                new AtomicInteger()), broken, clock(NOW.plusSeconds(2)), manager);
        List<SourceCandidateDTO> candidates = List.of(trusted(OFFICIAL));
        assertThrows(IllegalStateException.class, () -> rollback.verify(candidates));
        assertEquals(1, count("source_verification_attempts"));
        assertEquals(1, count("verified_source_registry"));
    }

    @Test
    void ambientTransactionIsRejectedBeforeNetwork() {
        var calls = new AtomicInteger();
        var service = verifier("<a href='" + ATS + "'>Jobs</a>", NOW, calls);
        List<SourceCandidateDTO> candidates = List.of(trusted(ATS));
        new TransactionTemplate(manager).executeWithoutResult(status ->
                assertThrows(IllegalStateException.class, () -> service.verify(candidates)));
        assertEquals(0, calls.get());
        assertEquals(0, count("source_verification_attempts"));
    }

    @Test
    void missingLockedSourceAfterInsertFailsSafelyAndRollsBackDecision() {
        SourceRegistryRepository missing = new SourceRegistryRepository(jdbc) {
            @Override public com.alexs.jobagent.source.entity.VerifiedSourceEntity findIdentityForUpdate(
                    com.alexs.jobagent.source.dto.AtsSourceIdentityDTO identity) {
                return null;
            }
        };
        var service = new SourceVerificationService(fetcher("<a href='" + ATS + "'>Jobs</a>",
                new AtomicInteger()), missing, clock(NOW), manager);
        List<SourceCandidateDTO> candidates = List.of(trusted(ATS));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> service.verify(candidates));
        assertEquals("Verified source missing after insert", failure.getMessage());
        assertEquals(0, count("verified_source_registry"));
        assertEquals(0, count("source_verification_decisions"));
        assertEquals(0, count("source_verification_attempts"));
    }

    @Test
    void malformedTrustClaimsCannotCreateOrDeactivateDomainDecisions() {
        var calls = new AtomicInteger();
        var service = verifier("<a href='" + ATS + "'>Jobs</a>", NOW, calls);
        var wrongDomain = new SourceCandidateDTO(ATS, "owner-confirmed", "other.example",
                OFFICIAL, "Acme Synthetic");
        assertEquals("TRUST_ROOT_AMBIGUOUS", service.verify(List.of(wrongDomain)).getFirst().reason());
        var unsafePage = new SourceCandidateDTO(ATS, "owner-confirmed", "acme.example",
                "http://acme.example/careers", "Acme Synthetic");
        assertEquals("UNSAFE_OFFICIAL_PAGE", service.verify(List.of(unsafePage)).getFirst().reason());
        assertEquals(0, calls.get());
        assertEquals(0, count("source_verification_decisions"));
        assertEquals(0, count("verified_source_registry"));
    }

    @Test
    void activeSourceLimitStopsBatchBeforeAnyAdapterRequest() {
        jdbc.update("""
                INSERT INTO verified_source_registry
                (platform, region, source_id, employer_domain, employer_name, official_page_url,
                 evidence_url, evidence_hash, verified_at, status, active)
                SELECT 'GREENHOUSE', 'global', 'synthetic-' || n, 'synthetic-' || n || '.example',
                       'Synthetic Employer', 'https://synthetic.example/careers',
                       'https://job-boards.greenhouse.io/synthetic', repeat('a', 64), ?, 'VERIFIED', TRUE
                FROM generate_series(1, 6) AS n
                """, java.sql.Timestamp.from(NOW));
        SourceRegistryService service = registry(NOW);
        IllegalStateException failure = assertThrows(IllegalStateException.class, service::importActive);
        assertEquals("At most five active sources can be imported per invocation", failure.getMessage());
        assertEquals(0, count("source_items"));
    }

    private SourceVerificationService verifier(String html, Instant at, AtomicInteger calls) {
        return new SourceVerificationService(fetcher(html, calls), new SourceRegistryRepository(jdbc), clock(at), manager);
    }

    private SourceVerificationService blockingVerifier(Instant at, CountDownLatch started,
                                                       CountDownLatch release) {
        return new SourceVerificationService(new SafeHtmlFetcher((uri, remaining) -> {
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new java.io.IOException("synthetic fetch timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("synthetic fetch interrupted", interrupted);
            }
            return new SafeHtmlFetcher.PageResponse(200, "text/html", null, "identity",
                    ("<a href='" + ATS + "'>Jobs</a>").getBytes(StandardCharsets.UTF_8));
        }), new SourceRegistryRepository(jdbc), clock(at), manager);
    }

    private SafeHtmlFetcher fetcher(String html, AtomicInteger calls) {
        return new SafeHtmlFetcher((uri, remaining) -> {
            calls.incrementAndGet();
            return new SafeHtmlFetcher.PageResponse(200, "text/html", null, "identity",
                    html.getBytes(StandardCharsets.UTF_8));
        });
    }

    private SourceRegistryService registry(Instant at) {
        return new SourceRegistryService(new SourceRegistryRepository(jdbc),
                new GreenhouseBatchService(board -> new GreenhouseFetchResultDTO(List.of(), 1),
                        greenhouseIngestion, clock(at)), lever, clock(at), manager);
    }

    private SourceCandidateDTO trusted(String url) {
        return new SourceCandidateDTO(url, "owner-confirmed", "acme.example", OFFICIAL, "Acme Synthetic");
    }

    private Clock clock(Instant instant) { return Clock.fixed(instant, ZoneOffset.UTC); }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
}
