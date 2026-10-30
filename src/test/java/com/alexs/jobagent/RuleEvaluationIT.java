package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.SearchProfileDTO;
import com.alexs.jobagent.evaluation.service.EvaluationService;
import com.alexs.jobagent.evaluation.service.ExtractionAwareEvaluationPolicy;
import com.alexs.jobagent.evaluation.service.ExtractionInputService;
import com.alexs.jobagent.evaluation.service.ExtractionOutputValidator;
import com.alexs.jobagent.evaluation.service.ExtractionPolicy;
import com.alexs.jobagent.evaluation.service.ProfileFileService;
import com.alexs.jobagent.evaluation.service.RuleEvaluationPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
class RuleEvaluationIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("job_agent_rules_test").withUsername("test_user")
            .withPassword("synthetic_test_secret_123");
    private static final String COUNTRY_REMOTE = """
            {"evaluationEvidence":{"arrangement":"REMOTE","remoteScope":"COUNTRY", "eligibleCountries":["DE"]}}
            """;
    private static final String EU_REMOTE = """
            {"evaluationEvidence":{"arrangement":"REMOTE","remoteScope":"EU", "eligibleCountries":["DE"]}}
            """;
    private static final String HYBRID = """
            {"evaluationEvidence":{"arrangement":"HYBRID","hybridLocation":"Berlin"}}
            """;
    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired ProfileFileService profiles;
    @Autowired EvaluationService evaluation;
    @Autowired ExtractionInputService extractionInputs;
    @Autowired ExtractionOutputValidator extractionValidator;
    @Autowired PlatformTransactionManager manager;
    @TempDir Path directory;

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE vacancy_evaluations, evaluation_profiles, vacancy_sources, source_items, "
                + "vacancies, employers, agent_runs RESTART IDENTITY CASCADE");
    }

    @Test
    void migrationReplayVersionsAndStaleEvaluations() throws Exception {
        assertEquals(13, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
        long vacancy = insert("Backend Engineer", COUNTRY_REMOTE);
        LoadedProfileDTO v1 = profile("v1", "Backend Engineer");
        assertEquals(1, evaluation.evaluate(v1, 0, 100, RuleEvaluationPolicy.VERSION).created());
        assertEquals(0, evaluation.evaluate(v1, 0, 100, RuleEvaluationPolicy.VERSION).created());
        assertEquals(1, count("vacancy_evaluations"));
        LoadedProfileDTO conflictingProfile = profile("v1", "Senior Java");
        assertThrows(IllegalArgumentException.class,
                () -> evaluation.evaluate(conflictingProfile, 0, 100, RuleEvaluationPolicy.VERSION));
        assertEquals(1, count("vacancy_evaluations"));
        assertEquals(1, evaluation.evaluate(profile("v2", "Backend Engineer"), 0, 100,
                RuleEvaluationPolicy.VERSION).created());
        assertEquals(1, evaluation.evaluate(v1, 0, 100, "rules-v2").created());
        assertEquals(3, count("vacancy_evaluations"));
        jdbc.update("UPDATE vacancies SET title = ? WHERE id = ?", "Office Coordinator", vacancy);
        assertTrue(evaluation.shortlist(v1, 10, RuleEvaluationPolicy.VERSION).suitable().isEmpty());
        assertTrue(evaluation.shortlist(v1, 10, RuleEvaluationPolicy.VERSION).needsReview().isEmpty());
        assertEquals(1, evaluation.evaluate(v1, 0, 100, RuleEvaluationPolicy.VERSION).created());
        assertEquals(4, count("vacancy_evaluations"));
        assertEquals(vacancy, evaluation.shortlist(v1, 10, RuleEvaluationPolicy.VERSION)
                .needsReview().getFirst().vacancyId());
    }

    @Test
    void currentPartialEvidenceSupersedesNoEvidenceAndPreservesHistory() throws Exception {
        long vacancy = insert("Backend Engineer", "{\"workplaceType\":\"remote\"}");
        LoadedProfileDTO profile = profile("v1", "Backend Engineer");
        String version = ExtractionAwareEvaluationPolicy.VERSION;
        assertEquals(1, evaluation.evaluate(profile, 0, 100, version).created());
        var unavailable = evaluation.shortlist(profile, 10, version).needsReview().getFirst();
        assertEquals("NONE", unavailable.extractionStatus());
        assertTrue(unavailable.unknowns().contains("EXTRACTION_EVIDENCE_UNAVAILABLE"));
        long work = insertExtraction(vacancy, "PARTIAL", ExtractionPolicy.POLICY_VERSION, """
                {"claims":[{"fact":"SKILL","value":"Java or Go","sourceField":"description",
                "quote":"Java or Go","uncertainty":"CONFLICTING",
                "skillEvidence":{"kind":"TECHNOLOGY","requirementStrength":"UNKNOWN",
                "sectionQuote":"","strengthQuote":""}},
                {"fact":"COUNTRY_PERMISSION","value":"DE","sourceField":"description",
                "quote":"Work in DE","uncertainty":"UNCERTAIN"}]}
                """, 1);
        assertTrue(evaluation.shortlist(profile, 10, version).needsReview().isEmpty());
        assertEquals(1, evaluation.evaluate(profile, 0, 100, version).created());
        assertEquals(0, evaluation.evaluate(profile, 0, 100, version).created());
        var current = evaluation.shortlist(profile, 10, version).needsReview().getFirst();
        assertEquals(work, current.extractionWorkId());
        assertEquals("PARTIAL", current.extractionStatus());
        assertEquals(1, current.rejectedCount());
        assertTrue(current.evidence().stream().anyMatch(e -> e.contains("Java or Go [UNKNOWN]")));
        assertTrue(current.unknowns().contains("COUNTRY_PERMISSION_NOT_INDEPENDENTLY_VERIFIED"));
        assertTrue(current.unknowns().contains("EXTRACTION_SKILL_CONFLICTING"));
        assertTrue(current.unknowns().contains("EXTRACTION_REJECTION_INVALID_FACT_VALUE"));
        assertEquals(2, count("vacancy_evaluations"));
        assertEquals(1, evaluation.evaluate(profile, 0, 100, RuleEvaluationPolicy.VERSION).created());
    }

    @Test
    void incompatibleAndStaleExtractionNeverBecomesCurrent() throws Exception {
        long vacancy = insert("Backend Engineer", "{}");
        LoadedProfileDTO profile = profile("v1", "Backend Engineer");
        insertExtraction(vacancy, "SUCCEEDED", "pilot-v5", "{\"claims\":[]}", 0);
        assertEquals(1, evaluation.evaluate(profile, 0, 100, ExtractionAwareEvaluationPolicy.VERSION).created());
        assertEquals("NONE", evaluation.shortlist(profile, 10, ExtractionAwareEvaluationPolicy.VERSION)
                .needsReview().getFirst().extractionStatus());
        insertExtraction(vacancy, "SUCCEEDED", ExtractionPolicy.POLICY_VERSION,
                "{\"claims\":[{\"fact\":\"SENIORITY\",\"value\":\"Senior\",\"sourceField\":\"title\","
                        + "\"quote\":\"Senior\",\"uncertainty\":\"STATED\"}]}", 0);
        assertEquals(1, evaluation.evaluate(profile, 0, 100, ExtractionAwareEvaluationPolicy.VERSION).created());
        jdbc.update("UPDATE vacancies SET title = 'Office Coordinator' WHERE id = ?", vacancy);
        assertTrue(evaluation.shortlist(profile, 10, ExtractionAwareEvaluationPolicy.VERSION).needsReview().isEmpty());
    }

    @Test
    void syntheticValidatedExtractionEvaluationAndShortlistReplay() throws Exception {
        long vacancy = insert("Senior Backend Engineer", COUNTRY_REMOTE);
        jdbc.update("UPDATE vacancies SET description = 'Java or Go experience' WHERE id = ?", vacancy);
        var input = extractionInputs.load(vacancy);
        var validated = extractionValidator.validate("""
                {"truncated":false,"claims":[{"fact":"SENIORITY","value":"Senior",
                "sourceField":"title","quote":"Senior Backend Engineer","uncertainty":"STATED"}]}
                """, input);
        long work = insertExtraction(vacancy, "SUCCEEDED", ExtractionPolicy.POLICY_VERSION,
                json.writeValueAsString(validated), 0);
        LoadedProfileDTO profile = profile("v1", "Backend Engineer");
        String version = ExtractionAwareEvaluationPolicy.VERSION;
        assertEquals(1, evaluation.evaluate(profile, 0, 100, version).created());
        var report = evaluation.shortlist(profile, 1, version);
        assertEquals(1, report.suitable().size());
        assertEquals("Senior Backend Engineer", report.suitable().getFirst().title());
        assertEquals(work, report.suitable().getFirst().extractionWorkId());
        assertTrue(report.suitable().getFirst().evidence().stream()
                .anyMatch(value -> value.contains("extracted SENIORITY")));
        assertEquals(0, evaluation.evaluate(profile, 0, 100, version).created());
        assertEquals(1, count("vacancy_evaluations"));
    }

    @Test
    void extractionAwareConcurrentReplayAndFailedInsertAreAtomic() throws Exception {
        long vacancy = insert("Backend Engineer", COUNTRY_REMOTE);
        insertExtraction(vacancy, "SUCCEEDED", ExtractionPolicy.POLICY_VERSION,
                "{\"claims\":[{\"fact\":\"FAMILY\",\"value\":\"Backend\","
                        + "\"sourceField\":\"title\",\"quote\":\"Backend Engineer\","
                        + "\"uncertainty\":\"STATED\"}]}", 0);
        LoadedProfileDTO profile = profile("v1", "Backend Engineer");
        jdbc.execute("CREATE FUNCTION reject_extraction_evaluation() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic insert failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_extraction_evaluation BEFORE INSERT ON vacancy_evaluations "
                + "FOR EACH ROW EXECUTE FUNCTION reject_extraction_evaluation()");
        assertThrows(RuntimeException.class, () -> evaluation.evaluate(profile, 0, 100,
                ExtractionAwareEvaluationPolicy.VERSION));
        assertEquals(0, count("vacancy_evaluations"));
        jdbc.execute("DROP TRIGGER reject_extraction_evaluation ON vacancy_evaluations");
        jdbc.execute("DROP FUNCTION reject_extraction_evaluation()");
        CountDownLatch start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> awaitAndEvaluate(start, profile,
                ExtractionAwareEvaluationPolicy.VERSION));
        var second = CompletableFuture.supplyAsync(() -> awaitAndEvaluate(start, profile,
                ExtractionAwareEvaluationPolicy.VERSION));
        start.countDown();
        assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        assertEquals(1, count("vacancy_evaluations"));
    }

    private long insertExtraction(long vacancy, String status, String policyVersion, String result, int rejected) {
        var input = extractionInputs.load(vacancy);
        return jdbc.queryForObject("INSERT INTO extraction_work(vacancy_id, source_item_id, vacancy_revision, "
                + "provider, model, prompt_version, schema_version, policy_version, input_json, kind, status, "
                + "result_json, accepted_count, rejected_count, rejection_diagnostics) "
                + "VALUES (?, ?, ?, 'ANTHROPIC', ?, ?, ?, ?, '{}'::jsonb, 'PRIMARY', ?, ?::jsonb, ?, ?, "
                + "?::jsonb) RETURNING id", Long.class,
                vacancy, input.sourceItemId(), input.revision(), ExtractionPolicy.PRIMARY_MODEL,
                ExtractionPolicy.PROMPT_VERSION, ExtractionPolicy.SCHEMA_VERSION, policyVersion,
                status, result, result.contains("\"fact\"") ? 1 : 0, rejected,
                rejected == 0 ? "[]" : "[{\"index\":3,\"category\":\"INVALID_FACT_VALUE\"}]");
    }

    @Test
    void concurrentReplayIsDatabaseDeduplicated() throws Exception {
        insert("Backend Engineer", COUNTRY_REMOTE);
        LoadedProfileDTO profile = profile("v1", "Backend Engineer");
        CountDownLatch start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> awaitAndEvaluate(start, profile));
        var second = CompletableFuture.supplyAsync(() -> awaitAndEvaluate(start, profile));
        start.countDown();
        assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        assertEquals(1, count("vacancy_evaluations"));
    }

    @Test
    void newerSourceSnapshotInvalidatesOlderEvaluationProvenance() throws Exception {
        long vacancy = insert("Backend Engineer", COUNTRY_REMOTE);
        LoadedProfileDTO profile = profile("v1", "Backend Engineer");
        assertEquals(1, evaluation.evaluate(profile, 0, 100, RuleEvaluationPolicy.VERSION).created());
        long previousSource = evaluation.shortlist(profile, 10, RuleEvaluationPolicy.VERSION)
                .suitable().getFirst().sourceItemId();
        long latestSource = jdbc.queryForObject("INSERT INTO source_items(source_namespace, source_item_id, "
                + "listing_namespace, listing_id, original_url, fetched_at, raw_payload, input_version, "
                + "normalization_version, input_digest) VALUES ('synthetic-board', 'snapshot-later', "
                + "'synthetic-board', 'listing-1', 'https://jobs.example/1', CURRENT_TIMESTAMP, ?::jsonb, "
                + "'synthetic-v1', 'rules-v1', repeat('b',64)) RETURNING id", Long.class, COUNTRY_REMOTE);
        jdbc.update("INSERT INTO vacancy_sources(vacancy_id, source_item_id) VALUES (?, ?)", vacancy, latestSource);
        assertNotEquals(previousSource, latestSource);
        assertTrue(evaluation.shortlist(profile, 10, RuleEvaluationPolicy.VERSION).suitable().isEmpty());
        assertEquals(1, evaluation.evaluate(profile, 0, 100, RuleEvaluationPolicy.VERSION).created());
        assertEquals(2, count("vacancy_evaluations"));
        assertEquals(latestSource, evaluation.shortlist(profile, 10, RuleEvaluationPolicy.VERSION)
                .suitable().getFirst().sourceItemId());
    }

    @Test
    void rejectedOlderSnapshotCannotBecomeCurrentEvidence() throws Exception {
        long vacancy = insert("Backend Engineer", COUNTRY_REMOTE);
        jdbc.update("UPDATE vacancies SET content_digest = repeat('c',64) WHERE id = ?", vacancy);
        jdbc.update("UPDATE source_items SET content_digest = repeat('c',64) WHERE source_item_id = 'snapshot-1'");
        LoadedProfileDTO profile = profile("v1", "Backend Engineer");
        assertEquals(1, evaluation.evaluate(profile, 0, 100, RuleEvaluationPolicy.VERSION).created());
        long acceptedSource = evaluation.shortlist(profile, 10, RuleEvaluationPolicy.VERSION)
                .suitable().getFirst().sourceItemId();
        long rejectedSource = jdbc.queryForObject("INSERT INTO source_items(source_namespace, source_item_id, "
                + "listing_namespace, listing_id, original_url, fetched_at, raw_payload, input_version, "
                + "normalization_version, input_digest, content_digest) VALUES ('synthetic-board', 'snapshot-stale', "
                + "'synthetic-board', 'listing-1', 'https://jobs.example/stale', CURRENT_TIMESTAMP, ?::jsonb, "
                + "'synthetic-v1', 'rules-v1', repeat('d',64), repeat('d',64)) RETURNING id",
                Long.class, COUNTRY_REMOTE);
        jdbc.update("INSERT INTO vacancy_sources(vacancy_id, source_item_id) VALUES (?, ?)", vacancy, rejectedSource);
        assertTrue(rejectedSource > acceptedSource);
        assertEquals(0, evaluation.evaluate(profile, 0, 100, RuleEvaluationPolicy.VERSION).created());
        var item = evaluation.shortlist(profile, 10, RuleEvaluationPolicy.VERSION).suitable().getFirst();
        assertEquals(acceptedSource, item.sourceItemId());
        assertEquals("https://jobs.example/1", item.originalUrl());
    }

    @Test
    void failedInsertRollsBackEvaluationAndCanRetry() throws Exception {
        insert("Backend Engineer", COUNTRY_REMOTE);
        LoadedProfileDTO profile = profile("v1", "Backend Engineer");
        jdbc.execute("CREATE FUNCTION reject_evaluation() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic evaluation failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_evaluation BEFORE INSERT ON vacancy_evaluations "
                + "FOR EACH ROW EXECUTE FUNCTION reject_evaluation()");
        assertThrows(RuntimeException.class, () -> evaluation.evaluate(profile, 0, 100, RuleEvaluationPolicy.VERSION));
        assertEquals(0, count("vacancy_evaluations"));
        jdbc.execute("DROP TRIGGER reject_evaluation ON vacancy_evaluations");
        jdbc.execute("DROP FUNCTION reject_evaluation()");
        assertEquals(1, evaluation.evaluate(profile, 0, 100, RuleEvaluationPolicy.VERSION).created());
    }

    @Test
    void shortlistSeparatesReviewOrdersStableRanksAndLimitsOutput() throws Exception {
        long review = insert("Backend Engineer", "{\"workplaceType\":\"remote\"}");
        long hybrid = insert("Backend Engineer", HYBRID);
        long eu = insert("Backend Engineer", EU_REMOTE);
        long country = insert("Backend Engineer", COUNTRY_REMOTE);
        long countryTie = insert("Backend Engineer", COUNTRY_REMOTE);
        insert("Backend Engineer Intern", COUNTRY_REMOTE);
        LoadedProfileDTO profile = profile("v1", "Backend Engineer");
        assertEquals(6, evaluation.evaluate(profile, 0, 100, RuleEvaluationPolicy.VERSION).created());
        var top = evaluation.shortlist(profile, 2, RuleEvaluationPolicy.VERSION);
        assertEquals(List.of(country, countryTie), top.suitable().stream().map(item -> item.vacancyId()).toList());
        assertTrue(top.needsReview().isEmpty());
        var all = evaluation.shortlist(profile, 5, RuleEvaluationPolicy.VERSION);
        assertEquals(List.of(country, countryTie, eu, hybrid),
                all.suitable().stream().map(item -> item.vacancyId()).toList());
        assertEquals(review, all.needsReview().getFirst().vacancyId());
        assertEquals("https://jobs.example/1", all.suitable().getFirst().originalUrl());
        assertTrue(all.suitable().getFirst().reasons().contains("CONFIRMED_COUNTRY_REMOTE"));
        assertTrue(all.needsReview().getFirst().unknowns().contains("WORK_ELIGIBILITY_UNCONFIRMED"));
        assertThrows(IllegalArgumentException.class, () -> evaluation.shortlist(profile, 51, RuleEvaluationPolicy.VERSION));
        LoadedProfileDTO unevaluatedProfile = profile("v2", "Backend Engineer");
        assertThrows(IllegalArgumentException.class,
                () -> evaluation.shortlist(unevaluatedProfile, 10, RuleEvaluationPolicy.VERSION));
    }

    @Test
    void preferredRolePrecedesEarlierReserveRoleAtSameWorkRank() throws Exception {
        long reserve = insert("Application Manager", COUNTRY_REMOTE);
        long preferred = insert("Backend Engineer", COUNTRY_REMOTE);
        LoadedProfileDTO profile = profile("v1", "Backend Engineer");
        assertTrue(reserve < preferred);
        assertEquals(2, evaluation.evaluate(profile, 0, 100, RuleEvaluationPolicy.VERSION).created());
        assertEquals("RESERVE", jdbc.queryForObject("SELECT role_classification FROM vacancy_evaluations "
                + "WHERE vacancy_id = ?", String.class, reserve));
        assertEquals("PREFERRED", jdbc.queryForObject("SELECT role_classification FROM vacancy_evaluations "
                + "WHERE vacancy_id = ?", String.class, preferred));
        var first = evaluation.shortlist(profile, 1, RuleEvaluationPolicy.VERSION);
        assertEquals(1, first.suitable().size());
        assertEquals(preferred, first.suitable().getFirst().vacancyId());
        assertEquals("PREFERRED", first.suitable().getFirst().roleClassification());
    }

    @Test
    void boundedCursorAndAmbientTransactionContract() throws Exception {
        for (int i = 0; i < 103; i++) {
            insert("Backend Engineer", COUNTRY_REMOTE);
        }
        LoadedProfileDTO profile = profile("v1", "Backend Engineer");
        assertThrows(IllegalStateException.class, () -> evaluateInAmbientTransaction(profile));
        assertEquals(0, count("vacancy_evaluations"));
        var first = evaluation.evaluate(profile, 0, 100, RuleEvaluationPolicy.VERSION);
        assertEquals(100, first.created());
        assertTrue(first.moreAvailable());
        var second = evaluation.evaluate(profile, first.lastVacancyId(), 100, RuleEvaluationPolicy.VERSION);
        assertEquals(3, second.created());
        assertFalse(second.moreAvailable());
        assertEquals(103, count("vacancy_evaluations"));
    }

    private int awaitAndEvaluate(CountDownLatch start, LoadedProfileDTO profile) {
        return awaitAndEvaluate(start, profile, RuleEvaluationPolicy.VERSION);
    }

    private int awaitAndEvaluate(CountDownLatch start, LoadedProfileDTO profile, String version) {
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return evaluation.evaluate(profile, 0, 100, version).created();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void evaluateInAmbientTransaction(LoadedProfileDTO profile) {
        new TransactionTemplate(manager).execute(status ->
                evaluation.evaluate(profile, 0, 100, RuleEvaluationPolicy.VERSION));
    }

    private LoadedProfileDTO profile(String version, String role) throws Exception {
        Path file = directory.resolve("profile-" + version + "-" + role.replace(' ', '-') + ".json");
        Files.writeString(file, json.writeValueAsString(new SearchProfileDTO("fictional", version, "DE",
                List.of(role), List.of("Application Manager"), List.of("Intern"), List.of("ONSITE"),
                List.of("Berlin"), true, new SearchProfileDTO.SalaryPreferenceDTO(60000, "EUR", "YEAR"))));
        return profiles.load(file.toString());
    }

    private long insert(String title, String raw) {
        long sequence = count("vacancies") + 1;
        long employer = jdbc.queryForObject("INSERT INTO employers(source_namespace, external_id, name) "
                + "VALUES ('synthetic-board', ?, 'Fictional Works') RETURNING id", Long.class, "employer-" + sequence);
        long vacancy = jdbc.queryForObject("INSERT INTO vacancies(employer_id, source_namespace, listing_id, title, "
                + "location) VALUES (?, 'synthetic-board', ?, ?, 'Remote') RETURNING id", Long.class,
                employer, "listing-" + sequence, title);
        long source = jdbc.queryForObject("INSERT INTO source_items(source_namespace, source_item_id, "
                + "listing_namespace, listing_id, original_url, fetched_at, raw_payload, input_version, "
                + "normalization_version, input_digest) VALUES ('synthetic-board', ?, 'synthetic-board', ?, "
                + "'https://jobs.example/1', CURRENT_TIMESTAMP, ?::jsonb, 'synthetic-v1', 'rules-v1', "
                + "repeat('a',64)) RETURNING id", Long.class, "snapshot-" + sequence, "listing-" + sequence, raw);
        jdbc.update("INSERT INTO vacancy_sources(vacancy_id, source_item_id) VALUES (?, ?)", vacancy, source);
        return vacancy;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
