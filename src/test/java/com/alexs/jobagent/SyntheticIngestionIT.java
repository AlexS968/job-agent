package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.source.dto.IngestionResultDTO;
import com.alexs.jobagent.source.dto.SyntheticInputDTO;
import com.alexs.jobagent.source.exception.IngestionConflictException;
import com.alexs.jobagent.source.exception.InvalidIngestionException;
import com.alexs.jobagent.source.service.SyntheticIngestionService;
import com.alexs.jobagent.vacancy.dto.EmployerDTO;
import com.alexs.jobagent.vacancy.dto.VacancyDTO;
import com.alexs.jobagent.vacancy.mapper.VacancyMapper;
import java.io.InputStream;
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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class SyntheticIngestionIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("job_agent_ingestion_test").withUsername("test_user")
            .withPassword("synthetic_test_secret_123");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired SyntheticIngestionService service;
    @Autowired VacancyMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void emptyDomain() {
        jdbc.execute("TRUNCATE vacancy_sources, source_items, vacancies, employers, agent_runs RESTART IDENTITY CASCADE");
    }

    @Test
    void emptyDatabaseMigratesAndStoresSourceAndVacancy() throws Exception {
        assertEquals(10, count("flyway_schema_history"));
        IngestionResultDTO result = service.ingest(fixture());
        assertTrue(result.sourceCreated());
        assertTrue(result.vacancyCreated());
        assertEquals(1, count("source_items"));
        assertEquals(1, count("employers"));
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("vacancy_sources"));
        assertEquals("role-001", jdbc.queryForObject("SELECT raw_payload ->> 'id' FROM source_items", String.class));
        assertEquals("synthetic-v1", jdbc.queryForObject("SELECT input_version FROM source_items", String.class));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM agent_runs", String.class));
    }

    @Test
    void identicalReplayIsNoOp() throws Exception {
        service.ingest(fixture());
        IngestionResultDTO replay = service.ingest(fixture());
        assertFalse(replay.sourceCreated());
        assertFalse(replay.vacancyCreated());
        assertEquals(1, count("source_items"));
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("vacancy_sources"));
        assertEquals(2, count("agent_runs"));
        assertEquals("REPLAYED", jdbc.queryForObject("SELECT result FROM agent_runs WHERE id = ?",
                String.class, replay.runId()));
    }

    @Test
    void distinctInputsCanReferenceOneStableListing() throws Exception {
        SyntheticInputDTO first = fixture();
        service.ingest(first);
        SyntheticInputDTO second = new SyntheticInputDTO(first.sourceNamespace(), "snapshot-002", first.originalUrl(),
                first.fetchedAt(), first.rawPayload(), first.inputVersion(), first.normalizationVersion(), first.vacancy());
        IngestionResultDTO result = service.ingest(second);
        assertTrue(result.sourceCreated());
        assertFalse(result.vacancyCreated());
        assertEquals(2, count("source_items"));
        assertEquals(1, count("vacancies"));
        assertEquals(2, count("vacancy_sources"));
    }

    @Test
    void employerNameDoesNotDetermineIdentity() throws Exception {
        SyntheticInputDTO first = fixture();
        service.ingest(first);
        EmployerDTO anotherEmployer = new EmployerDTO(first.vacancy().employer().sourceNamespace(),
                "employer-002", first.vacancy().employer().name());
        VacancyDTO anotherVacancy = new VacancyDTO(first.vacancy().sourceNamespace(), "role-002",
                first.vacancy().title(), first.vacancy().location(), anotherEmployer);
        SyntheticInputDTO second = new SyntheticInputDTO(first.sourceNamespace(), "snapshot-002", first.originalUrl(),
                first.fetchedAt(), first.rawPayload(), first.inputVersion(), first.normalizationVersion(), anotherVacancy);
        service.ingest(second);
        assertEquals(2, count("employers"));
        assertEquals(2, count("vacancies"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT external_id) FROM employers", Integer.class));
    }

    @Test
    void changedPayloadUnderExistingInputIdentityIsRejected() throws Exception {
        SyntheticInputDTO original = fixture();
        service.ingest(original);
        SyntheticInputDTO changed = new SyntheticInputDTO(original.sourceNamespace(), original.sourceItemId(),
                original.originalUrl(), original.fetchedAt(), json.readTree("{\"id\":\"role-001\",\"title\":\"Changed\"}"),
                original.inputVersion(), original.normalizationVersion(), original.vacancy());
        assertThrows(IngestionConflictException.class, () -> service.ingest(changed));
        assertEquals(1, count("source_items"));
        assertEquals(1, count("vacancies"));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs ORDER BY id DESC LIMIT 1", String.class));
        assertEquals("IDENTITY_CONFLICT", jdbc.queryForObject("SELECT error_code FROM agent_runs ORDER BY id DESC LIMIT 1", String.class));
    }

    @Test
    void conflictingNormalizedListingAcrossDistinctInputsIsRejected() throws Exception {
        SyntheticInputDTO original = fixture();
        service.ingest(original);
        VacancyDTO changedVacancy = new VacancyDTO(original.vacancy().sourceNamespace(),
                original.vacancy().listingId(), "Different title", original.vacancy().location(),
                original.vacancy().employer());
        SyntheticInputDTO second = new SyntheticInputDTO(original.sourceNamespace(), "snapshot-002",
                original.originalUrl(), original.fetchedAt(), original.rawPayload(), original.inputVersion(),
                original.normalizationVersion(), changedVacancy);
        assertThrows(IngestionConflictException.class, () -> service.ingest(second));
        assertEquals(1, count("source_items"));
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("vacancy_sources"));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs ORDER BY id DESC LIMIT 1", String.class));
    }

    @Test
    void concurrentReplayReliesOnUniqueConstraints() throws Exception {
        SyntheticInputDTO input = fixture();
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<IngestionResultDTO> one = CompletableFuture.supplyAsync(() -> awaitAndIngest(start, input));
        CompletableFuture<IngestionResultDTO> two = CompletableFuture.supplyAsync(() -> awaitAndIngest(start, input));
        start.countDown();
        IngestionResultDTO first = one.get(20, TimeUnit.SECONDS);
        IngestionResultDTO second = two.get(20, TimeUnit.SECONDS);
        assertNotEquals(first.sourceCreated(), second.sourceCreated());
        assertEquals(1, count("source_items"));
        assertEquals(1, count("vacancies"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE status = 'SUCCEEDED'", Integer.class));
    }

    @Test
    void databaseFailureAfterPartialWriteRollsBackDomainAndMarksRunFailed() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_vacancy() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic test failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_vacancy BEFORE INSERT ON vacancies "
                + "FOR EACH ROW EXECUTE FUNCTION reject_vacancy()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> service.ingest(fixture()));
            assertEquals(0, count("source_items"));
            assertEquals(0, count("employers"));
            assertEquals(0, count("vacancies"));
            assertEquals(0, count("vacancy_sources"));
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs", String.class));
            assertEquals("DATABASE_ERROR", jdbc.queryForObject("SELECT error_code FROM agent_runs", String.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_vacancy ON vacancies");
            jdbc.execute("DROP FUNCTION reject_vacancy()");
        }
    }

    @Test
    void ambientTransactionIsRejectedBeforeRunAndCallerRollbackLeavesNoWrites() throws Exception {
        SyntheticInputDTO input = fixture();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO agent_runs(status) VALUES ('RUNNING')");
            assertThrows(IllegalStateException.class, () -> service.ingest(input));
            assertEquals(1, count("agent_runs"));
            status.setRollbackOnly();
        });
        assertEquals(0, count("agent_runs"));
        assertEquals(0, count("source_items"));
        assertEquals(0, count("employers"));
        assertEquals(0, count("vacancies"));
        assertEquals(0, count("vacancy_sources"));
    }

    @Test
    void failedTrackingPreservesOriginalIngestionFailure() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_vacancy() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic original failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_vacancy BEFORE INSERT ON vacancies "
                + "FOR EACH ROW EXECUTE FUNCTION reject_vacancy()");
        jdbc.execute("CREATE FUNCTION reject_failed_run() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic tracking failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_failed_run BEFORE UPDATE ON agent_runs "
                + "FOR EACH ROW WHEN (NEW.status = 'FAILED') EXECUTE FUNCTION reject_failed_run()");
        try {
            var original = assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> service.ingest(fixture()));
            assertTrue(original.getMessage().contains("synthetic original failure"));
            assertEquals(1, original.getSuppressed().length);
            assertEquals(0, count("source_items"));
            assertEquals(0, count("employers"));
            assertEquals(0, count("vacancies"));
            assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM agent_runs", String.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_failed_run ON agent_runs");
            jdbc.execute("DROP FUNCTION reject_failed_run()");
            jdbc.execute("DROP TRIGGER reject_vacancy ON vacancies");
            jdbc.execute("DROP FUNCTION reject_vacancy()");
        }
    }

    @Test
    void invalidInputFailsBeforeDomainWrites() throws Exception {
        SyntheticInputDTO input = fixture();
        SyntheticInputDTO invalid = new SyntheticInputDTO(input.sourceNamespace(), input.sourceItemId(),
                "not-an-https-url", input.fetchedAt(), input.rawPayload(), input.inputVersion(),
                input.normalizationVersion(), input.vacancy());
        assertThrows(InvalidIngestionException.class, () -> service.ingest(invalid));
        assertEquals(0, count("source_items"));
        assertEquals("INVALID_INPUT", jdbc.queryForObject("SELECT error_code FROM agent_runs", String.class));
    }

    @Test
    void mapperConvertsIdentityAndNormalizedFields() throws Exception {
        SyntheticInputDTO input = fixture();
        var employer = mapper.toEntity(input.vacancy().employer());
        var vacancy = mapper.toEntity(input.vacancy(), 42L);
        assertEquals("employer-001", employer.externalId());
        assertEquals("Example Works", employer.name());
        assertEquals(42L, vacancy.employerDbId());
        assertEquals("role-001", vacancy.listingId());
        assertEquals("Backend Engineer", vacancy.title());
        assertNull(mapper.toEntity((com.alexs.jobagent.vacancy.dto.EmployerDTO) null));
    }

    private SyntheticInputDTO fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/synthetic/source-item.json")) {
            assertNotNull(input);
            return json.readValue(input, SyntheticInputDTO.class);
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private IngestionResultDTO awaitAndIngest(CountDownLatch start, SyntheticInputDTO input) {
        try {
            start.await();
            return service.ingest(input);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent test interrupted", interrupted);
        }
    }
}
