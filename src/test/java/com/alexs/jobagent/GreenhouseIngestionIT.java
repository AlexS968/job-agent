package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.source.dto.GreenhouseBoardDTO;
import com.alexs.jobagent.source.dto.GreenhouseListingDTO;
import com.alexs.jobagent.source.dto.GreenhouseFetchResultDTO;
import com.alexs.jobagent.source.exception.IngestionConflictException;
import com.alexs.jobagent.source.exception.InvalidIngestionException;
import com.alexs.jobagent.source.service.GreenhouseBatchService;
import com.alexs.jobagent.source.service.GreenhouseIngestionService;
import com.alexs.jobagent.source.mapper.GreenhouseVacancyMapper;
import com.alexs.jobagent.vacancy.dto.EmployerDTO;
import com.alexs.jobagent.outbox.service.LocalEventDispatcher;
import java.time.Clock;
import java.util.List;
import com.alexs.jobagent.vacancy.dto.VacancyChangeDTO;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class GreenhouseIngestionIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("job_agent_greenhouse_test").withUsername("test_user")
            .withPassword("synthetic_test_secret_123");
    private static final Instant FETCHED = Instant.parse("2026-10-22T10:00:00Z");
    private static final GreenhouseBoardDTO BOARD = new GreenhouseBoardDTO("example-board", "Example Board Inc");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired GreenhouseIngestionService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired LocalEventDispatcher dispatcher;
    @Autowired Clock clock;
    @Autowired GreenhouseVacancyMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void emptyDomain() {
        jdbc.execute("TRUNCATE vacancy_sources, source_items, vacancies, employers, agent_runs RESTART IDENTITY CASCADE");
    }

    @Test
    void firstListingStoresDescriptionProvenanceAndAtomicDiscovery() {
        assertEquals(11, count("flyway_schema_history"));
        var result = service.ingest(BOARD, listing("1", "Engineer", "2026-10-21T10:00:00Z"), FETCHED);
        assertEquals(VacancyChangeDTO.CREATED, result.change());
        assertEquals(1, count("source_items"));
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("vacancy_sources"));
        assertEquals(1, count("outbox_events"));
        assertEquals("VacancyDiscovered", jdbc.queryForObject("SELECT event_type FROM outbox_events", String.class));
        assertEquals("CREATED", jdbc.queryForObject("SELECT result FROM agent_runs", String.class));
        assertEquals("<p>Example role</p>", jdbc.queryForObject("SELECT description FROM vacancies", String.class));
        assertEquals("greenhouse:example-board", jdbc.queryForObject("SELECT source_namespace FROM vacancies", String.class));
        assertEquals("greenhouse-board", jdbc.queryForObject("SELECT source_namespace FROM employers", String.class));
        assertEquals("2026-10-21T10:00:00Z", jdbc.queryForObject(
                "SELECT to_char(source_updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM source_items",
                String.class));
        assertTrue(jdbc.queryForObject("SELECT jsonb_exists(raw_payload, 'content') FROM source_items", Boolean.class));
    }

    @Test
    void mapperConvertsSourceFieldsWithoutInventingUnknownLocation() {
        GreenhouseListingDTO unknown = new GreenhouseListingDTO("42", "Engineer", null, null,
                "https://job-boards.greenhouse.io/example-board/jobs/42", null,
                json.readTree("{\"id\":42}"));
        var mapped = mapper.toVacancy(unknown, "greenhouse:example-board",
                new EmployerDTO("greenhouse-board", "example-board", "Example Board Inc"));
        assertEquals("42", mapped.listingId());
        assertEquals("Engineer", mapped.title());
        assertNull(mapped.location());
        assertEquals("example-board", mapped.employer().employerId());
    }

    @Test
    void invalidDirectInputsCreateFailedRunsWithoutDomainWrites() {
        GreenhouseListingDTO valid = listing("1", "Engineer", "2026-10-21T10:00:00Z");
        GreenhouseListingDTO blankTitle = new GreenhouseListingDTO(valid.id(), " ", valid.location(),
                valid.description(), valid.originalUrl(), valid.updatedAt(), valid.rawPayload());
        GreenhouseListingDTO credentialUrl = new GreenhouseListingDTO(valid.id(), valid.title(), valid.location(),
                valid.description(), "https://user@example.org/jobs/1", valid.updatedAt(), valid.rawPayload());
        GreenhouseListingDTO nonObject = new GreenhouseListingDTO(valid.id(), valid.title(), valid.location(),
                valid.description(), valid.originalUrl(), valid.updatedAt(), json.readTree("[]"));
        assertThrows(InvalidIngestionException.class, () -> service.ingest(BOARD, blankTitle, FETCHED));
        assertThrows(InvalidIngestionException.class, () -> service.ingest(BOARD, credentialUrl, FETCHED));
        assertThrows(InvalidIngestionException.class, () -> service.ingest(BOARD, nonObject, FETCHED));
        assertThrows(InvalidIngestionException.class,
                () -> service.ingest(new GreenhouseBoardDTO("bad/token", "Synthetic Inc"), valid, FETCHED));
        assertEquals(4, count("agent_runs"));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs "
                + "WHERE status = 'FAILED' AND error_code = 'INVALID_INPUT'", Integer.class));
        assertEquals(0, count("source_items"));
        assertEquals(0, count("vacancies"));
        assertEquals(0, count("outbox_events"));
    }

    @Test
    void nestedObjectOrderIsCanonicalButArrayOrderIsSourceContent() {
        GreenhouseListingDTO base = listing("1", "Engineer", "2026-10-21T10:00:00Z");
        String common = "{\"id\":1,\"title\":\"Engineer\",\"absolute_url\":\""
                + base.originalUrl() + "\",\"updated_at\":\"2026-10-21T10:00:00Z\",";
        GreenhouseListingDTO first = new GreenhouseListingDTO(base.id(), base.title(), base.location(),
                base.description(), base.originalUrl(), base.updatedAt(),
                json.readTree(common + "\"metadata\":[{\"a\":1,\"b\":2},{\"a\":3}]}"));
        GreenhouseListingDTO reordered = new GreenhouseListingDTO(base.id(), base.title(), base.location(),
                base.description(), base.originalUrl(), base.updatedAt(),
                json.readTree(common + "\"metadata\":[{\"b\":2,\"a\":1},{\"a\":3}]}"));
        GreenhouseListingDTO changedOrder = new GreenhouseListingDTO(base.id(), base.title(), base.location(),
                base.description(), base.originalUrl(), base.updatedAt(),
                json.readTree(common + "\"metadata\":[{\"a\":3},{\"a\":1,\"b\":2}]}"));
        assertEquals(VacancyChangeDTO.CREATED, service.ingest(BOARD, first, FETCHED).change());
        assertEquals(VacancyChangeDTO.UNCHANGED, service.ingest(BOARD, reordered, FETCHED).change());
        assertThrows(IngestionConflictException.class, () -> service.ingest(BOARD, changedOrder, FETCHED));
        assertEquals(1, count("source_items"));
        assertEquals(1, count("outbox_events"));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs ORDER BY id DESC LIMIT 1",
                String.class));
    }

    @Test
    void ambientTransactionIsRejectedBeforeRunCreation() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class,
                    () -> service.ingest(BOARD, listing("1", "Engineer", "2026-10-21T10:00:00Z"), FETCHED));
            status.setRollbackOnly();
        });
        assertEquals(0, count("agent_runs"));
        assertEquals(0, count("source_items"));
    }

    @Test
    void reorderedJsonAndDifferentFetchTimeAreNoOp() {
        service.ingest(BOARD, listing("1", "Engineer", "2026-10-21T10:00:00Z"), FETCHED);
        JsonNode reordered = json.readTree("{\"content\":\"<p>Example role</p>\",\"title\":\"Engineer\","
                + "\"id\":1,\"updated_at\":\"2026-10-21T10:00:00Z\",\"location\":{\"name\":\"Berlin\"},"
                + "\"absolute_url\":\"https://boards.greenhouse.io/example-board/jobs/1\"}");
        var replay = new GreenhouseListingDTO("1", "Engineer", "Berlin", "<p>Example role</p>",
                "https://boards.greenhouse.io/example-board/jobs/1", Instant.parse("2026-10-21T10:00:00Z"), reordered);
        assertEquals(VacancyChangeDTO.UNCHANGED,
                service.ingest(BOARD, replay, FETCHED.plusSeconds(3600)).change());
        assertEquals(1, count("source_items"));
        assertEquals(1, count("outbox_events"));
        assertEquals(2, count("agent_runs"));
        assertEquals("UNCHANGED", jdbc.queryForObject("SELECT result FROM agent_runs ORDER BY id DESC LIMIT 1",
                String.class));
    }

    @Test
    void differentBoardsWithSamePostIdDoNotCollide() {
        service.ingest(BOARD, listing("1", "Engineer", "2026-10-21T10:00:00Z"), FETCHED);
        service.ingest(new GreenhouseBoardDTO("another-board", "Another Inc"),
                listing("1", "Engineer", "2026-10-21T10:00:00Z"), FETCHED);
        assertEquals(2, count("vacancies"));
        assertEquals(2, count("employers"));
        assertEquals(2, count("outbox_events"));
    }

    @Test
    void changedListingUpdatesOnceAndOldVersionCannotOverwrite() {
        service.ingest(BOARD, listing("1", "Engineer", "2026-10-21T10:00:00Z"), FETCHED);
        var changed = listing("1", "Senior Engineer", "2026-10-22T09:00:00Z");
        assertEquals(VacancyChangeDTO.UPDATED, service.ingest(BOARD, changed, FETCHED).change());
        assertEquals(VacancyChangeDTO.UNCHANGED, service.ingest(BOARD, changed, FETCHED.plusSeconds(1)).change());
        assertEquals(VacancyChangeDTO.STALE,
                service.ingest(BOARD, listing("1", "Old title", "2026-10-20T09:00:00Z"), FETCHED).change());
        assertEquals("Senior Engineer", jdbc.queryForObject("SELECT title FROM vacancies", String.class));
        assertEquals(3, count("source_items"));
        assertEquals(2, count("outbox_events"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'VacancyUpdated'",
                Integer.class));
        assertEquals("STALE", jdbc.queryForObject("SELECT result FROM agent_runs ORDER BY id DESC LIMIT 1",
                String.class));
    }

    @Test
    void laterTimestampWithSameContentAdvancesVersionWithoutNewEvent() {
        service.ingest(BOARD, listing("1", "Engineer", "2026-10-21T10:00:00Z"), FETCHED);
        assertEquals(VacancyChangeDTO.UNCHANGED,
                service.ingest(BOARD, listing("1", "Engineer", "2026-10-22T09:00:00Z"), FETCHED).change());
        assertEquals(2, count("source_items"));
        assertEquals(1, count("outbox_events"));
        assertEquals(Instant.parse("2026-10-22T09:00:00Z"), jdbc.queryForObject(
                "SELECT source_updated_at FROM vacancies", java.sql.Timestamp.class).toInstant());
    }

    @Test
    void datedVersionSupersedesUnknownVersionAndUnknownCannotOverwriteDatedVersion() {
        String url = "https://boards.greenhouse.io/example-board/jobs/1";
        GreenhouseListingDTO undated = new GreenhouseListingDTO("1", "Engineer", "Berlin",
                "<p>Example role</p>", url, null, json.readTree("{\"id\":1,\"title\":\"Engineer\","
                        + "\"absolute_url\":\"" + url + "\",\"content\":\"<p>Example role</p>\","
                        + "\"location\":{\"name\":\"Berlin\"}}"));
        assertEquals(VacancyChangeDTO.CREATED, service.ingest(BOARD, undated, FETCHED).change());
        assertEquals(VacancyChangeDTO.UNCHANGED,
                service.ingest(BOARD, listing("1", "Engineer", "2026-10-21T10:00:00Z"), FETCHED).change());
        assertEquals(VacancyChangeDTO.UNCHANGED, service.ingest(BOARD, undated, FETCHED.plusSeconds(1)).change());
        service.ingest(BOARD, listing("2", "Engineer", "2026-10-21T10:00:00Z"), FETCHED);
        GreenhouseListingDTO newUndated = new GreenhouseListingDTO("2", "Engineer", "Berlin",
                "<p>Example role</p>", "https://boards.greenhouse.io/example-board/jobs/2", null,
                json.readTree("{\"id\":2,\"title\":\"Engineer\",\"absolute_url\":"
                        + "\"https://boards.greenhouse.io/example-board/jobs/2\","
                        + "\"content\":\"<p>Example role</p>\",\"location\":{\"name\":\"Berlin\"}}"));
        assertEquals(VacancyChangeDTO.STALE, service.ingest(BOARD, newUndated, FETCHED).change());
        assertEquals(2, count("vacancies"));
        assertEquals(2, count("outbox_events"));
        assertEquals(Instant.parse("2026-10-21T10:00:00Z"), jdbc.queryForObject(
                "SELECT source_updated_at FROM vacancies WHERE listing_id = '1'", java.sql.Timestamp.class).toInstant());
    }

    @Test
    void updatedEventIsHandledByLocalConsumerWithoutDuplicateDiscoveryEffect() {
        service.ingest(BOARD, listing("1", "Engineer", "2026-10-21T10:00:00Z"), FETCHED);
        service.ingest(BOARD, listing("1", "Senior Engineer", "2026-10-22T09:00:00Z"), FETCHED);
        assertEquals(2, dispatcher.dispatch(20).completed());
        assertEquals(2, count("consumer_deliveries"));
        assertEquals(1, count("synthetic_vacancy_observations"));
    }

    @Test
    void batchReportsPartialFailureAndRetainsSuccessfulListing() {
        GreenhouseListingDTO first = listing("1", "Engineer", "2026-10-21T10:00:00Z");
        GreenhouseListingDTO conflicting = listing("1", "Different", "2026-10-21T10:00:00Z");
        GreenhouseBatchService batch = new GreenhouseBatchService(board ->
                new GreenhouseFetchResultDTO(List.of(first, conflicting), 1), service, clock);
        var result = batch.run("example-board|Example Board Inc");
        assertEquals(2, result.fetched());
        assertEquals(1, result.created());
        assertEquals(1, result.rejected());
        assertEquals(0, result.failed());
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("outbox_events"));
    }

    @Test
    void databaseFailureStopsLaterListingsButKeepsEarlierCommittedListing() {
        jdbc.execute("CREATE FUNCTION reject_second_greenhouse_vacancy() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.listing_id = '2' THEN RAISE EXCEPTION 'synthetic test failure'; END IF; "
                + "RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_second_greenhouse_vacancy BEFORE INSERT ON vacancies "
                + "FOR EACH ROW EXECUTE FUNCTION reject_second_greenhouse_vacancy()");
        try {
            GreenhouseBatchService batch = new GreenhouseBatchService(board -> new GreenhouseFetchResultDTO(
                    List.of(listing("1", "Engineer", "2026-10-21T10:00:00Z"),
                            listing("2", "Engineer", "2026-10-21T10:00:00Z"),
                            listing("3", "Engineer", "2026-10-21T10:00:00Z")), 1), service, clock);
            var result = batch.run("example-board|Example Board Inc");
            assertEquals(3, result.fetched());
            assertEquals(1, result.created());
            assertEquals(1, result.failed());
            assertEquals(0, result.rejected());
            assertEquals(1, count("vacancies"));
            assertEquals(1, count("source_items"));
            assertEquals(1, count("outbox_events"));
            assertEquals(2, count("agent_runs"));
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs ORDER BY id DESC LIMIT 1",
                    String.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_second_greenhouse_vacancy ON vacancies");
            jdbc.execute("DROP FUNCTION reject_second_greenhouse_vacancy()");
        }
    }

    @Test
    void outboxFailureRollsBackSourceAndVacancy() {
        jdbc.execute("CREATE FUNCTION reject_greenhouse_event() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic event failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_greenhouse_event BEFORE INSERT ON outbox_events "
                + "FOR EACH ROW EXECUTE FUNCTION reject_greenhouse_event()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> service.ingest(BOARD, listing("1", "Engineer", "2026-10-21T10:00:00Z"), FETCHED));
            assertEquals(0, count("source_items"));
            assertEquals(0, count("vacancies"));
            assertEquals(0, count("outbox_events"));
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs", String.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_greenhouse_event ON outbox_events");
            jdbc.execute("DROP FUNCTION reject_greenhouse_event()");
        }
    }

    @Test
    void equalTimestampDifferentContentIsRejectedWithoutPartialWrites() {
        service.ingest(BOARD, listing("1", "Engineer", "2026-10-21T10:00:00Z"), FETCHED);
        assertThrows(IngestionConflictException.class,
                () -> service.ingest(BOARD, listing("1", "Different", "2026-10-21T10:00:00Z"), FETCHED));
        assertEquals(1, count("source_items"));
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("outbox_events"));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs ORDER BY id DESC LIMIT 1", String.class));
    }

    @Test
    void concurrentReplayUsesDatabaseIdentity() throws Exception {
        GreenhouseListingDTO listing = listing("1", "Engineer", "2026-10-21T10:00:00Z");
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<VacancyChangeDTO> one = CompletableFuture.supplyAsync(() -> awaitIngest(start, listing));
        CompletableFuture<VacancyChangeDTO> two = CompletableFuture.supplyAsync(() -> awaitIngest(start, listing));
        start.countDown();
        assertNotEquals(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("source_items"));
        assertEquals(1, count("outbox_events"));
    }

    @Test
    void concurrentVersionsKeepNewestContent() throws Exception {
        GreenhouseListingDTO older = listing("1", "Engineer", "2026-10-21T10:00:00Z");
        GreenhouseListingDTO newer = listing("1", "Senior Engineer", "2026-10-22T09:00:00Z");
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<VacancyChangeDTO> first = CompletableFuture.supplyAsync(() -> awaitIngest(start, older));
        CompletableFuture<VacancyChangeDTO> second = CompletableFuture.supplyAsync(() -> awaitIngest(start, newer));
        start.countDown();
        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);
        assertEquals("Senior Engineer", jdbc.queryForObject("SELECT title FROM vacancies", String.class));
        assertEquals(2, count("source_items"));
        assertEquals(1, count("vacancies"));
    }

    @Test
    void failureAfterSourceInsertRollsBackSourceDomainAndEvent() {
        jdbc.execute("CREATE FUNCTION reject_greenhouse_vacancy() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic test failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_greenhouse_vacancy BEFORE INSERT ON vacancies "
                + "FOR EACH ROW EXECUTE FUNCTION reject_greenhouse_vacancy()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> service.ingest(BOARD, listing("1", "Engineer", "2026-10-21T10:00:00Z"), FETCHED));
            assertEquals(0, count("source_items"));
            assertEquals(0, count("vacancies"));
            assertEquals(0, count("employers"));
            assertEquals(0, count("outbox_events"));
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs", String.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_greenhouse_vacancy ON vacancies");
            jdbc.execute("DROP FUNCTION reject_greenhouse_vacancy()");
        }
    }

    private VacancyChangeDTO awaitIngest(CountDownLatch start, GreenhouseListingDTO listing) {
        try {
            start.await();
            return service.ingest(BOARD, listing, FETCHED).change();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private GreenhouseListingDTO listing(String id, String title, String updated) {
        String url = "https://boards.greenhouse.io/example-board/jobs/" + id;
        JsonNode raw = json.readTree("{\"id\":" + id + ",\"title\":\"" + title + "\","
                + "\"absolute_url\":\"" + url + "\",\"updated_at\":\"" + updated + "\","
                + "\"content\":\"<p>Example role</p>\",\"location\":{\"name\":\"Berlin\"}}");
        return new GreenhouseListingDTO(id, title, "Berlin", "<p>Example role</p>", url,
                Instant.parse(updated), raw);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
