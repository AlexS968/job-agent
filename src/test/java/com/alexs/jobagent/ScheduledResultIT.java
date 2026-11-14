package com.alexs.jobagent;

import com.alexs.jobagent.batch.dto.BatchPipelineResultDTO;
import com.alexs.jobagent.batch.repository.ScheduledBatchRepository;
import com.alexs.jobagent.batch.service.BatchReportFormatter;
import com.alexs.jobagent.batch.service.ScheduledResultService;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.SearchProfileDTO;
import com.alexs.jobagent.evaluation.dto.ShortlistDTO;
import com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO;
import com.alexs.jobagent.outbox.dto.BacklogDTO;
import com.alexs.jobagent.outbox.dto.SqsQueueBacklogDTO;
import com.alexs.jobagent.source.dto.ActiveSourceImportResultDTO;
import java.time.LocalDate;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledResultIT {
    private static final String PAGE = "https://page.example.test/";
    private static final String REVISION = "a".repeat(64);
    private static final LocalDate FIRST = LocalDate.of(2026, 11, 17);

    @Test
    void revisionReplayIsNotNewAndFirstEligibilityIsLabeledSeparately() {
        try (var postgres = database()) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
            Flyway.configure().dataSource(dataSource).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            var repository = new ScheduledBatchRepository(jdbc);
            var service = new ScheduledResultService(repository, new BatchReportFormatter(),
                    new DataSourceTransactionManager(dataSource));
            long first = vacancy(jdbc, 1);
            long second = vacancy(jdbc, 2);
            var de = profile("alex-de", "DE", "v1", "a");
            var at = profile("alex-at", "AT", "v1", "b");

            var initial = service.prepare(FIRST, de, at, List.of(candidate(first, REVISION)),
                    List.of(candidate(first, REVISION)), PAGE, run());
            assertEquals(1, initial.distinctNew());
            assertEquals(1, initial.germanyNew());
            assertEquals(1, initial.austriaNew());
            assertEquals(2, count(jdbc, "scheduled_result_items"));
            service.markPublishing(FIRST);
            assertThrows(IllegalStateException.class, service::requireNoUnresolvedPublication);
            service.markPublished(FIRST, "synthetic-message-1");
            service.requireNoUnresolvedPublication();

            var zero = service.prepare(FIRST.plusDays(2), de, at, List.of(candidate(first, "b".repeat(64))),
                    List.of(candidate(first, "b".repeat(64))), PAGE, run());
            assertEquals(0, zero.distinctNew());
            assertEquals(0, zero.distinctRelevant());
            assertTrue(zero.body().contains("No newly stored opportunities"));
            service.markPublishing(FIRST.plusDays(2));
            service.markPublished(FIRST.plusDays(2), "synthetic-message-2");
            assertEquals(2, count(jdbc, "scheduled_result_items"));

            var atOnly = service.prepare(FIRST.plusDays(5), de, at,
                    List.of(candidate(first, REVISION)),
                    List.of(candidate(first, REVISION), candidate(second, REVISION)), PAGE, run());
            assertEquals(0, atOnly.distinctNew());
            assertEquals(0, atOnly.germanyNew());
            assertEquals(0, atOnly.austriaNew());
            assertEquals(1, atOnly.austriaRelevant());
            assertTrue(atOnly.body().contains("Austria: 0 new; 1 newly relevant"));
            service.markPublishing(FIRST.plusDays(5));
            service.markPublished(FIRST.plusDays(5), "synthetic-message-at");

            long newlyStored = vacancy(jdbc, 3);
            jdbc.update("UPDATE source_items SET created_at = "
                    + "(SELECT MAX(finished_at) + interval '1 second' FROM scheduled_result_reports "
                    + "WHERE status = 'PUBLISHED') WHERE id = "
                    + "(SELECT source_item_id FROM vacancy_sources WHERE vacancy_id = ?)", newlyStored);
            var changed = service.prepare(FIRST.plusDays(7), de, at,
                    List.of(candidate(first, "c".repeat(64)), candidate(second, REVISION),
                            candidate(newlyStored, REVISION)),
                    List.of(candidate(first, "c".repeat(64)), candidate(second, REVISION),
                            candidate(newlyStored, REVISION)), PAGE, run());
            assertEquals(1, changed.distinctNew());
            assertEquals(1, changed.germanyNew());
            assertEquals(1, changed.austriaNew());
            assertEquals(1, changed.germanyRelevant());
            assertEquals(0, changed.austriaRelevant());
            service.markPublishing(FIRST.plusDays(7));
            service.markPublished(FIRST.plusDays(7), "synthetic-message-3");

            long previouslyStored = vacancy(jdbc, 4);
            jdbc.update("UPDATE source_items SET created_at = CURRENT_TIMESTAMP - interval '1 day' WHERE id = "
                    + "(SELECT source_item_id FROM vacancy_sources WHERE vacancy_id = ?)", previouslyStored);
            var newProfile = profile("alex-at", "AT", "v2", "c");
            var version = service.prepare(FIRST.plusDays(9), de, newProfile,
                    List.of(candidate(second, REVISION)),
                    List.of(candidate(first, REVISION), candidate(previouslyStored, REVISION)), PAGE, run());
            assertEquals(0, version.distinctNew());
            assertEquals(0, version.germanyNew());
            assertEquals(0, version.austriaNew());
            assertEquals(1, version.austriaRelevant());
            service.markPublishing(FIRST.plusDays(9));
            service.markPublished(FIRST.plusDays(9), "synthetic-message-4");
            assertEquals(7, count(jdbc, "scheduled_result_items"));
            assertEquals(5, count(jdbc, "scheduled_result_reports"));
            assertTrue(repository.wasPublished("alex-de", first));
            assertTrue(repository.wasPublished("alex-at", first));
            assertFalse(repository.wasPublished("alex-de", previouslyStored));
        }
    }

    @Test
    void historicalEmailBecomesBaselineAndUncertainPublicationBlocksFurtherDates() {
        try (var postgres = database()) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
            Flyway.configure().dataSource(dataSource).target("17").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            long vacancy = vacancy(jdbc, 1);
            jdbc.update("INSERT INTO scheduled_batch_reports(schedule_date,status,profile_id,profile_version," 
                    + "criteria_hash,report_text,sns_message_id) VALUES (?, 'PUBLISHED','alex-de','v1',?,?,?)",
                    FIRST.minusDays(7), "a".repeat(64), "historical text", "historical-message");
            Flyway.configure().dataSource(dataSource).load().migrate();
            var repository = new ScheduledBatchRepository(jdbc);
            var service = new ScheduledResultService(repository, new BatchReportFormatter(),
                    new DataSourceTransactionManager(dataSource));
            var result = service.prepare(FIRST, profile("alex-de", "DE", "v1", "a"),
                    profile("alex-at", "AT", "v1", "b"), List.of(candidate(vacancy, REVISION)),
                    List.of(candidate(vacancy, REVISION)), PAGE, run());
            assertEquals(0, result.germanyNew());
            assertEquals(1, result.austriaNew());
            assertEquals("BASELINE", jdbc.queryForObject("SELECT disposition FROM scheduled_result_items "
                    + "WHERE profile_id = 'alex-de'", String.class));
            assertEquals("historical text", jdbc.queryForObject("SELECT report_text FROM scheduled_batch_reports "
                    + "WHERE profile_id = 'alex-de'", String.class));
            service.markPublishing(FIRST);
            service.markUncertain(FIRST);
            assertThrows(IllegalStateException.class, service::requireNoUnresolvedPublication);
            assertThrows(IllegalStateException.class, () -> service.prepare(FIRST.plusDays(2),
                    profile("alex-de", "DE", "v1", "a"), profile("alex-at", "AT", "v1", "b"),
                    List.of(), List.of(), PAGE, run()));
            assertEquals(1, count(jdbc, "scheduled_result_reports"));
        }
    }

    @Test
    void failedItemWriteRollsBackPreparedEmailAndAllItemRows() {
        try (var postgres = database()) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
            Flyway.configure().dataSource(dataSource).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            long vacancy = vacancy(jdbc, 1);
            var repository = new ScheduledBatchRepository(jdbc);
            var service = new ScheduledResultService(repository, new BatchReportFormatter(),
                    new DataSourceTransactionManager(dataSource));
            var de = profile("alex-de", "DE", "v1", "a");
            var at = profile("alex-at", "AT", "v1", "b");
            assertThrows(RuntimeException.class, () -> service.prepare(FIRST, de, at,
                    List.of(candidate(vacancy, REVISION), candidate(vacancy + 1000, REVISION)),
                    List.of(), PAGE, run()));
            assertEquals(0, count(jdbc, "scheduled_result_reports"));
            assertEquals(0, count(jdbc, "scheduled_result_items"));
            var prepared = service.prepare(FIRST, de, at, List.of(candidate(vacancy, REVISION)),
                    List.of(), PAGE, run());
            assertEquals(1, prepared.germanyNew());
        }
    }

    private static PostgreSQLContainer database() {
        return new PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("job_agent_result_test").withUsername("test_user")
                .withPassword("synthetic_test_secret_123");
    }

    private static long vacancy(JdbcTemplate jdbc, int number) {
        long employer = jdbc.queryForObject("INSERT INTO employers(source_namespace, external_id, name) "
                + "VALUES ('synthetic-test', ?, 'Fictional Works') RETURNING id", Long.class,
                "employer-" + number);
        long vacancy = jdbc.queryForObject("INSERT INTO vacancies(employer_id, source_namespace, listing_id, title, "
                + "location) VALUES (?, 'synthetic-test', ?, 'Backend Engineer', 'Remote') RETURNING id",
                Long.class, employer, "listing-" + number);
        long source = jdbc.queryForObject("INSERT INTO source_items(source_namespace, source_item_id, "
                + "listing_namespace, listing_id, original_url, fetched_at, raw_payload, input_version, "
                + "normalization_version, input_digest) VALUES ('synthetic-test', ?, 'synthetic-test', ?, "
                + "'https://jobs.example.test/1', CURRENT_TIMESTAMP, '{}'::jsonb, 'synthetic-v1', "
                + "'synthetic-v1', repeat('a', 64)) RETURNING id", Long.class,
                "item-" + number, "listing-" + number);
        jdbc.update("INSERT INTO vacancy_sources(vacancy_id, source_item_id) VALUES (?, ?)", vacancy, source);
        return vacancy;
    }

    private static LoadedProfileDTO profile(String id, String country, String version, String hash) {
        return new LoadedProfileDTO(new SearchProfileDTO(id, version, country, List.of("Backend Engineer"),
                List.of(), List.of(), List.of(), List.of(), false, null), hash.repeat(64));
    }

    private static VacancyEvaluationDTO candidate(long vacancyId, String revision) {
        return new VacancyEvaluationDTO(vacancyId, vacancyId, revision, "SUITABLE", 1, "PREFERRED",
                List.of("PREFERRED_ROLE"), List.of(), List.of(), "https://jobs.example.test/1");
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static BatchPipelineResultDTO run() {
        return new BatchPipelineResultDTO("EVALUATION_SWEEP", new ActiveSourceImportResultDTO(0, 0, 0, 0, 0),
                0, 0, 0, 0, 0, new BacklogDTO(0, 0, 0, 0, 0), new SqsQueueBacklogDTO(0, 0, 0),
                new ShortlistDTO(List.of(), List.of()));
    }
}
