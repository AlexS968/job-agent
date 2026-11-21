package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alexs.jobagent.evaluation.dto.SearchProfileDTO;
import com.alexs.jobagent.evaluation.service.EvaluationService;
import com.alexs.jobagent.evaluation.service.ProfileFileService;
import com.alexs.jobagent.evaluation.service.RuleEvaluationPolicy;
import com.alexs.jobagent.source.dto.HimalayasImportResultDTO;
import com.alexs.jobagent.source.service.BoundedSourceHttpTransport;
import com.alexs.jobagent.source.service.HimalayasIntakeService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class HimalayasIntakeIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("himalayas_test").withUsername("test_user").withPassword("synthetic_test_secret_123");
    private static final String POSTING_URL = "https://himalayas.app/companies/example/jobs/platform";
    private static final Instant OBSERVED = Instant.parse("2026-11-21T12:00:00Z");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired HimalayasIntakeService intake;
    @Autowired EvaluationService evaluation;
    @Autowired ProfileFileService profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired CommandLineRunner runner;
    @MockitoBean BoundedSourceHttpTransport transport;
    @MockitoBean Clock clock;

    @BeforeEach
    void clearDomain() {
        jdbc.execute("TRUNCATE source_items, vacancies, employers, agent_runs RESTART IDENTITY CASCADE");
        when(clock.instant()).thenReturn(OBSERVED);
    }

    @Test
    void importsOverlappingPagesOnlyOnceAndRevisionsUnderTheSameIdentity() throws Exception {
        var platform = job(POSTING_URL, "Software Engineer", "Develop Java backend services.",
                List.of("Germany", "Austria"));
        var other = job("https://himalayas.app/companies/example/jobs/recruiter", "Recruiter",
                "Hire Java engineers", List.of());
        respond(page(platform, other), page(platform), page());

        assertEquals(new HimalayasImportResultDTO(3, 3, 2, 1, 2, 0, 0, 0, Map.of()), intake.run());
        var requests = ArgumentCaptor.forClass(URI.class);
        verify(transport, times(3)).get(requests.capture(), eq(Duration.between(OBSERVED, Instant.MAX)));
        assertEquals(List.of("q=Java&sort=recent&page=1&country=DE&exclude_worldwide=true",
                "q=Java&sort=recent&page=1&country=AT&exclude_worldwide=true",
                "q=Java&sort=recent&page=1&worldwide=true"),
                requests.getAllValues().stream().map(URI::getRawQuery).toList());
        assertTrue(requests.getAllValues().stream().allMatch(uri -> "https".equals(uri.getScheme())
                && "himalayas.app".equals(uri.getHost()) && "/jobs/api/search".equals(uri.getPath())));
        assertEquals(2, count("vacancies WHERE source_namespace='himalayas'"));
        assertEquals(2, count("vacancy_candidate_assessments"));
        assertEquals(1, count("source_items WHERE original_url = ? AND source_namespace = 'himalayas' "
                + "AND raw_payload->'locationRestrictions' = '[\"Germany\",\"Austria\"]'::jsonb", POSTING_URL));
        assertEquals(2, count("outbox_events"));
        long vacancyId = jdbc.queryForObject("SELECT id FROM vacancies WHERE title='Software Engineer'", Long.class);

        respond(page(platform, other), page(platform), page());
        assertEquals(new HimalayasImportResultDTO(3, 3, 2, 1, 0, 0, 2, 0, Map.of()), intake.run());
        assertEquals(2, count("source_items"));
        assertEquals(2, count("outbox_events"));
        var changed = job(POSTING_URL, "Senior Software Engineer", "Develop Java backend services.",
                List.of("Germany", "Austria"));
        when(clock.instant()).thenReturn(OBSERVED.plusSeconds(10));
        respond(page(changed), page(changed), page());
        assertEquals(new HimalayasImportResultDTO(3, 2, 1, 1, 0, 1, 0, 0, Map.of()), intake.run());
        assertEquals(1, count("vacancies WHERE id=? AND title='Senior Software Engineer'", vacancyId));
        assertEquals(3, count("source_items"));
        assertEquals(3, count("outbox_events"));
    }

    @Test
    void failedFilterDoesNotDropSuccessfulPagesOrConfirmWorkEligibility() throws Exception {
        var platform = job(POSTING_URL, "Software Engineer", "Develop Java backend services.", List.of("Germany"));
        respond(page(platform), new BoundedSourceHttpTransport.Response(503, new byte[0], Map.of()),
                page(platform, job("https://example.test/companies/example/jobs/invalid", "Engineer", "Java", List.of())));

        assertEquals(new HimalayasImportResultDTO(3, 3, 1, 1, 1, 0, 0, 1, Map.of("AT", "HTTP_503")), intake.run());
        verify(transport, times(3)).get(any(URI.class), any(Duration.class));
        assertEquals(1, count("source_items"));
        for (String country : List.of("DE", "AT")) {
            var profile = profiles.loadJson(json.writeValueAsString(new SearchProfileDTO("sample-" + country, "v1", country,
                    List.of("Backend Engineer"), List.of(), List.of(), List.of(), List.of(), false, null)));
            assertEquals(1, evaluation.evaluate(profile, 0, 10, RuleEvaluationPolicy.VERSION).created());
            var review = evaluation.shortlist(profile, 5, RuleEvaluationPolicy.VERSION).needsReview().getFirst();
            assertEquals(POSTING_URL, review.originalUrl());
            assertEquals("RESERVE", review.roleClassification());
            assertTrue(review.unknowns().contains("WORK_ELIGIBILITY_UNCONFIRMED"));
            assertTrue(evaluation.reportCandidates(profile, RuleEvaluationPolicy.VERSION).isEmpty());
        }
        assertEquals(2, count("vacancy_evaluations WHERE country_eligibility='UNKNOWN'"));
    }

    @Test
    void manualCommandFailsOnAnOversizedPageAndDoesNotImportIt() throws Exception {
        var posting = job(POSTING_URL, "Software Engineer", "Develop Java backend services.", List.of());
        var oversized = new BoundedSourceHttpTransport.Response(200,
                json.writeValueAsBytes(Map.of("jobs", java.util.Collections.nCopies(21, posting))), Map.of());
        respond(page(), oversized, page());

        assertEquals("Himalayas intake completed with failures", assertThrows(IllegalStateException.class,
                () -> runner.run("--mode=himalayas-intake")).getMessage());
        verify(transport, times(3)).get(any(URI.class), any(Duration.class));
        assertEquals(0, count("vacancies"));
        assertEquals(0, count("outbox_events"));
    }

    @Test
    void doesNotStartPersistenceAfterTheBatchDeadline() throws Exception {
        Instant deadline = OBSERVED.plusSeconds(2);
        var response = page(job(POSTING_URL, "Java Engineer", "Develop backend services.", List.of()));
        doAnswer(call -> {
            when(clock.instant()).thenReturn(deadline);
            return response;
        }).when(transport).get(any(URI.class), eq(Duration.ofSeconds(2)));

        var result = intake.runUntil(deadline);

        assertEquals(1, result.requests());
        assertEquals(1, result.distinct());
        assertEquals(0, result.created());
        assertEquals(Map.of("AT", "DEADLINE_REACHED", "WORLDWIDE", "DEADLINE_REACHED"), result.failedFilters());
        assertEquals(0, count("agent_runs"));
        assertEquals(0, count("outbox_events"));
        verify(transport).get(any(URI.class), any(Duration.class));
    }

    @Test
    void storedPostingDropsOutOfActiveReviewAtExpiryWithoutReimport() throws Exception {
        Instant expiry = OBSERVED.plusSeconds(60);
        String applicationLink = POSTING_URL + "?source=synthetic-apply";
        var posting = new HashMap<>(job(POSTING_URL, "Software Engineer", "Develop Java backend services.",
                List.of("Germany")));
        posting.put("expiryDate", expiry.getEpochSecond());
        posting.put("applicationLink", applicationLink);
        respond(page(posting), page(), page());
        assertEquals(1, intake.run().created());
        assertEquals(1, count("source_items WHERE original_url = ? AND apply_url = ? "
                + "AND (raw_payload->>'expiryDate')::bigint = ?", POSTING_URL, applicationLink, expiry.getEpochSecond()));

        var profile = profiles.loadJson(json.writeValueAsString(new SearchProfileDTO("sample-de", "v1", "DE",
                List.of("Backend Engineer"), List.of(), List.of(), List.of(), List.of(), false, null)));
        assertEquals(1, evaluation.evaluate(profile, 0, 10, RuleEvaluationPolicy.VERSION).created());
        var review = evaluation.shortlist(profile, 5, RuleEvaluationPolicy.VERSION).needsReview().getFirst();
        assertEquals("Himalayas", review.sourceAttribution());
        assertEquals(POSTING_URL, review.originalUrl());
        assertEquals(applicationLink, review.applicationLink());

        when(clock.instant()).thenReturn(expiry);
        var expired = evaluation.shortlist(profile, 5, RuleEvaluationPolicy.VERSION);
        assertTrue(expired.needsReview().isEmpty());
        assertTrue(expired.suitable().isEmpty());
        assertEquals(1, count("vacancy_evaluations"));
        assertEquals(1, count("vacancies"));
        verify(transport, times(3)).get(any(URI.class), any(Duration.class));
    }

    private void respond(BoundedSourceHttpTransport.Response de, BoundedSourceHttpTransport.Response at,
                         BoundedSourceHttpTransport.Response worldwide) throws Exception {
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            URI uri = call.getArgument(0);
            if (uri.getRawQuery().contains("country=DE")) {
                return de;
            }
            return uri.getRawQuery().contains("country=AT") ? at : worldwide;
        }).when(transport).get(any(URI.class), any(Duration.class));
    }

    private static Map<String, Object> job(String url, String title, String description, List<String> locations) {
        return Map.of("guid", url, "title", title, "description", description, "companyName", "Example",
                "locationRestrictions", locations, "timezoneRestrictions", List.of());
    }

    @SafeVarargs
    private final BoundedSourceHttpTransport.Response page(Map<String, Object>... jobs) {
        return new BoundedSourceHttpTransport.Response(200, json.writeValueAsString(Map.of("jobs", List.of(jobs)))
                .getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private int count(String table, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class, args);
    }
}
