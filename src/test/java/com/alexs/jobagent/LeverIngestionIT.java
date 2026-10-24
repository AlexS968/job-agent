package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.source.dto.LeverFetchResultDTO;
import com.alexs.jobagent.source.dto.LeverListingDTO;
import com.alexs.jobagent.source.dto.LeverSiteDTO;
import com.alexs.jobagent.source.exception.InvalidIngestionException;
import com.alexs.jobagent.source.mapper.LeverVacancyMapper;
import com.alexs.jobagent.source.service.LeverBatchService;
import com.alexs.jobagent.source.service.LeverIngestionService;
import com.alexs.jobagent.vacancy.dto.EmployerDTO;
import com.alexs.jobagent.vacancy.dto.VacancyChangeDTO;
import com.alexs.jobagent.vacancy.exception.VacancyConflictException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
class LeverIngestionIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("job_agent_lever_test").withUsername("test_user")
            .withPassword("synthetic_test_secret_123");
    private static final LeverSiteDTO SITE = new LeverSiteDTO("global", "example", "Example Inc");
    private static final Instant OBSERVED = Instant.parse("2026-10-22T10:00:00Z");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired LeverIngestionService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired LeverVacancyMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void emptyDomain() {
        jdbc.execute("TRUNCATE vacancy_sources, source_items, vacancies, employers, agent_runs RESTART IDENTITY CASCADE");
    }

    @Test
    void emptyDatabaseMigratesAndFirstObservationStoresProvenanceAndOutbox() {
        assertEquals(6, count("flyway_schema_history"));
        assertEquals(VacancyChangeDTO.CREATED, service.ingest(SITE, listing("1", "Engineer"), OBSERVED).change());
        assertEquals(1, count("source_items"));
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("vacancy_sources"));
        assertEquals(1, count("outbox_events"));
        assertEquals("lever:global:example", jdbc.queryForObject("SELECT source_namespace FROM vacancies", String.class));
        assertEquals("https://jobs.lever.co/example/1/apply",
                jdbc.queryForObject("SELECT apply_url FROM source_items", String.class));
        assertTrue(jdbc.queryForObject("SELECT jsonb_exists(raw_payload, 'extra') FROM source_items", Boolean.class));
        assertNull(jdbc.queryForObject("SELECT source_updated_at FROM source_items", java.sql.Timestamp.class));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM agent_runs", String.class));
    }

    @Test
    void replayAndLaterUnchangedObservationDoNotDuplicate() {
        assertEquals(VacancyChangeDTO.CREATED, service.ingest(SITE, listing("1", "Engineer"), OBSERVED).change());
        assertEquals(VacancyChangeDTO.UNCHANGED, service.ingest(SITE, listing("1", "Engineer"), OBSERVED).change());
        assertEquals(VacancyChangeDTO.UNCHANGED,
                service.ingest(SITE, listing("1", "Engineer"), OBSERVED.plusSeconds(10)).change());
        assertEquals(1, count("source_items"));
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("vacancy_sources"));
        assertEquals(1, count("outbox_events"));
        assertEquals(3, count("agent_runs"));
        assertEquals(OBSERVED.plusSeconds(10), jdbc.queryForObject(
                "SELECT observation_started_at FROM vacancies", java.sql.Timestamp.class).toInstant());
    }

    @Test
    void subMicrosecondObservationReplaysAtStoredPrecision() {
        Instant observed = OBSERVED.plusNanos(123_456_789);
        Instant stored = OBSERVED.plusNanos(123_456_000);
        assertEquals(VacancyChangeDTO.CREATED, service.ingest(SITE, listing("1", "Engineer"), observed).change());
        assertEquals(VacancyChangeDTO.UNCHANGED, service.ingest(SITE, listing("1", "Engineer"), observed).change());
        assertEquals(stored, jdbc.queryForObject("SELECT observation_started_at FROM vacancies",
                java.sql.Timestamp.class).toInstant());
        assertEquals(stored, jdbc.queryForObject("SELECT fetched_at FROM source_items",
                java.sql.Timestamp.class).toInstant());
        assertEquals(1, count("source_items"));
        assertEquals(1, count("outbox_events"));
    }

    @Test
    void laterObservationAcrossMicrosecondBoundaryUpdatesWithoutRoundingCollision() {
        Instant earlier = OBSERVED.plusNanos(999);
        Instant later = OBSERVED.plusNanos(1_001);
        assertEquals(VacancyChangeDTO.CREATED,
                service.ingest(SITE, listing("1", "Engineer"), earlier).change());
        assertEquals(VacancyChangeDTO.UPDATED,
                service.ingest(SITE, listing("1", "Senior Engineer"), later).change());
        assertEquals(OBSERVED.plusNanos(1_000), jdbc.queryForObject(
                "SELECT observation_started_at FROM vacancies", java.sql.Timestamp.class).toInstant());
        assertEquals("Senior Engineer", jdbc.queryForObject("SELECT title FROM vacancies", String.class));
        assertEquals(2, count("source_items"));
        assertEquals(2, count("vacancy_sources"));
        assertEquals(2, count("outbox_events"));
    }

    @Test
    void distinctObservationsWithinOneMicrosecondShareOrderingSlot() {
        Instant first = OBSERVED.plusNanos(1_100);
        Instant second = OBSERVED.plusNanos(1_900);
        service.ingest(SITE, listing("1", "Engineer"), first);
        assertEquals(VacancyChangeDTO.UNCHANGED,
                service.ingest(SITE, listing("1", "Engineer"), second).change());
        assertEquals(OBSERVED.plusNanos(1_000), jdbc.queryForObject(
                "SELECT observation_started_at FROM vacancies", java.sql.Timestamp.class).toInstant());
        assertEquals(1, count("source_items"));
        assertEquals(1, count("outbox_events"));
    }

    @Test
    void conflictingContentWithinOneMicrosecondIsRejectedAtomically() {
        service.ingest(SITE, listing("1", "Engineer"), OBSERVED.plusNanos(1_100));
        assertThrows(VacancyConflictException.class,
                () -> service.ingest(SITE, listing("1", "Conflicting"), OBSERVED.plusNanos(1_900)));
        assertEquals("Engineer", jdbc.queryForObject("SELECT title FROM vacancies", String.class));
        assertEquals(1, count("source_items"));
        assertEquals(1, count("vacancy_sources"));
        assertEquals(1, count("outbox_events"));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs ORDER BY id DESC LIMIT 1",
                String.class));
    }

    @Test
    void slowerEarlierSubMicrosecondObservationCannotOverwriteNewerAcceptedContent() {
        assertEquals(VacancyChangeDTO.CREATED,
                service.ingest(SITE, listing("1", "Newer"), OBSERVED.plusNanos(2_100)).change());
        assertEquals(VacancyChangeDTO.STALE,
                service.ingest(SITE, listing("1", "Older"), OBSERVED.plusNanos(1_999)).change());
        assertEquals("Newer", jdbc.queryForObject("SELECT title FROM vacancies", String.class));
        assertEquals(OBSERVED.plusNanos(2_000), jdbc.queryForObject(
                "SELECT observation_started_at FROM vacancies", java.sql.Timestamp.class).toInstant());
        assertEquals(1, count("outbox_events"));
    }

    @Test
    void reorderedJsonObjectKeysDoNotCreateNewSnapshotOrEvent() {
        service.ingest(SITE, listing("1", "Engineer"), OBSERVED);
        String url = "https://jobs.lever.co/example/1";
        LeverListingDTO reordered = new LeverListingDTO("1", "Engineer", null, null, null,
                url, url + "/apply", json.readTree("{\"extra\":{\"flag\":true},\"applyUrl\":\""
                + url + "/apply\",\"hostedUrl\":\"" + url
                + "\",\"text\":\"Engineer\",\"id\":\"1\"}"));
        assertEquals(VacancyChangeDTO.UNCHANGED,
                service.ingest(SITE, reordered, OBSERVED.plusSeconds(5)).change());
        assertEquals(1, count("source_items"));
        assertEquals(1, count("outbox_events"));
    }

    @Test
    void newerChangeWinsOlderObservationCannotOverwriteAndEqualTimeConflicts() {
        service.ingest(SITE, listing("1", "Engineer"), OBSERVED);
        assertEquals(VacancyChangeDTO.UPDATED,
                service.ingest(SITE, listing("1", "Senior Engineer"), OBSERVED.plusSeconds(10)).change());
        assertEquals(VacancyChangeDTO.STALE,
                service.ingest(SITE, listing("1", "Old Engineer"), OBSERVED.plusSeconds(5)).change());
        assertThrows(VacancyConflictException.class,
                () -> service.ingest(SITE, listing("1", "Conflicting"), OBSERVED.plusSeconds(10)));
        assertEquals("Senior Engineer", jdbc.queryForObject("SELECT title FROM vacancies", String.class));
        assertEquals(3, count("source_items"));
        assertEquals(2, count("outbox_events"));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs ORDER BY id DESC LIMIT 1", String.class));
    }

    @Test
    void regionAndSiteArePartOfStableIdentityAndMapperKeepsUnknownNull() {
        service.ingest(SITE, listing("1", "Engineer"), OBSERVED);
        LeverSiteDTO another = new LeverSiteDTO("eu", "example", "Example EU");
        String euRaw = "{\"id\":\"1\",\"text\":\"Engineer\",\"hostedUrl\":\""
                + "https://jobs.eu.lever.co/example/1\"}";
        LeverListingDTO eu = new LeverListingDTO("1", "Engineer", null, null, null,
                "https://jobs.eu.lever.co/example/1", null, json.readTree(euRaw));
        service.ingest(another, eu, OBSERVED);
        assertEquals(2, count("vacancies"));
        assertEquals(2, count("employers"));
        var mapped = mapper.toVacancy(eu, "lever:eu:example",
                new EmployerDTO("lever-site:eu", "example", "Example EU"));
        assertNull(mapped.location());
        assertEquals("1", mapped.listingId());
        assertEquals("lever:eu:example", mapped.sourceNamespace());
    }

    @Test
    void invalidInputCreatesFailedRunAndAmbientTransactionCreatesNoRun() {
        LeverListingDTO valid = listing("1", "Engineer");
        LeverListingDTO invalid = new LeverListingDTO(valid.id(), " ", valid.location(), valid.workplaceType(),
                valid.description(), valid.hostedUrl(), valid.applyUrl(), valid.rawPayload());
        assertThrows(InvalidIngestionException.class, () -> service.ingest(SITE, invalid, OBSERVED));
        assertEquals(1, count("agent_runs"));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs", String.class));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class, () -> service.ingest(SITE, valid, OBSERVED));
            status.setRollbackOnly();
        });
        assertEquals(1, count("agent_runs"));
        assertEquals(0, count("source_items"));
    }

    @Test
    void outboxFailureRollsBackSourceAndVacancy() {
        jdbc.execute("CREATE FUNCTION reject_lever_event() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic event failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_lever_event BEFORE INSERT ON outbox_events "
                + "FOR EACH ROW EXECUTE FUNCTION reject_lever_event()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> service.ingest(SITE, listing("1", "Engineer"), OBSERVED));
            assertEquals(0, count("source_items"));
            assertEquals(0, count("vacancies"));
            assertEquals(0, count("outbox_events"));
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs", String.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_lever_event ON outbox_events");
            jdbc.execute("DROP FUNCTION reject_lever_event()");
        }
    }

    @Test
    void concurrentReplayUsesUniqueConstraints() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> ingestAfter(start));
        var second = CompletableFuture.supplyAsync(() -> ingestAfter(start));
        start.countDown();
        assertEquals(1, List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))
                .stream().filter(change -> change == VacancyChangeDTO.CREATED).count());
        assertEquals(1, count("source_items"));
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("outbox_events"));
    }

    @Test
    void slowerEarlierObservationCannotOverwriteLaterAcceptedContent() {
        assertEquals(VacancyChangeDTO.CREATED,
                service.ingest(SITE, listing("1", "Newer"), OBSERVED.plusSeconds(10)).change());
        assertEquals(VacancyChangeDTO.STALE,
                service.ingest(SITE, listing("1", "Older"), OBSERVED).change());
        assertEquals("Newer", jdbc.queryForObject("SELECT title FROM vacancies", String.class));
        assertEquals(1, count("outbox_events"));
    }

    @Test
    void repeatedContentChangeEmitsOnlyOneUpdateEvent() {
        service.ingest(SITE, listing("1", "Engineer"), OBSERVED);
        assertEquals(VacancyChangeDTO.UPDATED,
                service.ingest(SITE, listing("1", "Senior Engineer"), OBSERVED.plusSeconds(10)).change());
        assertEquals(VacancyChangeDTO.UNCHANGED,
                service.ingest(SITE, listing("1", "Senior Engineer"), OBSERVED.plusSeconds(20)).change());
        assertEquals(2, count("outbox_events"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'VacancyUpdated'", Integer.class));
    }

    @Test
    void batchReportsPartialFailureWithoutUndoingEarlierListing() {
        LeverBatchService batch = new LeverBatchService(site -> new LeverFetchResultDTO(
                List.of(listing("1", "Engineer"), listing("1", "Different")), 1, OBSERVED), service);
        var result = batch.run("global|example|Example Inc");
        assertEquals(1, result.created());
        assertEquals(1, result.rejected());
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("outbox_events"));
    }

    private VacancyChangeDTO ingestAfter(CountDownLatch start) {
        try {
            start.await();
            return service.ingest(SITE, listing("1", "Engineer"), OBSERVED).change();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private LeverListingDTO listing(String id, String title) {
        String url = "https://jobs.lever.co/example/" + id;
        String raw = "{\"id\":\"" + id + "\",\"text\":\"" + title + "\",\"hostedUrl\":\""
                + url + "\",\"applyUrl\":\"" + url + "/apply\",\"extra\":{\"flag\":true}}";
        return new LeverListingDTO(id, title, null, null, null, url, url + "/apply", json.readTree(raw));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
