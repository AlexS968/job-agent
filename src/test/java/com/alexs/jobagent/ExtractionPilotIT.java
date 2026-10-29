package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alexs.jobagent.evaluation.dto.ExtractionGatewayResponseDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionCompletionDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionUsageDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionWorkIdentityDTO;
import com.alexs.jobagent.evaluation.exception.ExtractionFailureException;
import com.alexs.jobagent.evaluation.exception.ExtractionValidationException;
import com.alexs.jobagent.evaluation.repository.ExtractionRepository;
import com.alexs.jobagent.evaluation.service.AnthropicExtractionGateway;
import com.alexs.jobagent.evaluation.service.ExtractionInputService;
import com.alexs.jobagent.evaluation.service.ExtractionCancellationGuard;
import com.alexs.jobagent.evaluation.service.ExtractionCostEstimator;
import com.alexs.jobagent.evaluation.service.ExtractionOutputValidator;
import com.alexs.jobagent.evaluation.service.ExtractionPilotService;
import com.alexs.jobagent.evaluation.service.ExtractionPolicy;
import com.alexs.jobagent.evaluation.service.ConservativeExtractionCostEstimator;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
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
class ExtractionPilotIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("job_agent_extraction_test").withUsername("test_user")
            .withPassword("synthetic_test_secret_123");
    private static final String RESPONSE = """
            {"truncated":false,"claims":[{"fact":"TITLE","value":"Backend Engineer","sourceField":"title",
            "quote":"Backend Engineer","uncertainty":"STATED"}]}
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
    @Autowired ExtractionInputService inputs;
    @Autowired ExtractionOutputValidator validator;
    @Autowired ExtractionRepository repository;
    @Autowired PlatformTransactionManager manager;
    private AnthropicExtractionGateway gateway;
    private ExtractionPilotService service;
    private final ExtractionCostEstimator syntheticEstimator = (input, model) -> syntheticReservation(model);

    private static long syntheticReservation(String model) {
        // A fixed bound isolates the persistence and recovery tests from HTTP estimation.
        return ExtractionPolicy.cost(model, 32_000, ExtractionPolicy.MAX_OUTPUT_TOKENS);
    }

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE extraction_attempts, extraction_work, vacancy_sources, source_items, "
                + "vacancies, employers, agent_runs RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE extraction_pilot_budget SET reserved_micro_usd = 0, observed_micro_usd = 0, "
                + "attempts = 0, primary_distinct = 0, comparison_distinct = 0, "
                + "active_work_id = NULL, active_claim_token = NULL, active_since = NULL, "
                + "halted_reason = NULL WHERE id = 1");
        gateway = mock(AnthropicExtractionGateway.class);
        when(gateway.requestBody(any(), anyString())).thenReturn(new byte[100]);
        service = new ExtractionPilotService(inputs, validator, gateway, repository, json, manager,
                Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC), () -> "synthetic-key",
                syntheticEstimator, new ExtractionCancellationGuard());
    }

    @Test
    void successfulReplayAndComparisonUseSameRevisionAndDurableBudget() {
        long vacancy = insert();
        when(gateway.extract(any(), eq(ExtractionPolicy.PRIMARY_MODEL), anyString()))
                .thenReturn(new ExtractionGatewayResponseDTO(RESPONSE, 100, 50, "req-primary"));
        when(gateway.extract(any(), eq(ExtractionPolicy.COMPARISON_MODEL), anyString()))
                .thenReturn(new ExtractionGatewayResponseDTO(RESPONSE, 120, 60, "req-comparison"));
        var first = run(vacancy, false, false);
        assertFalse(first.reused());
        assertTrue(jdbc.queryForObject("SELECT input_json::text FROM extraction_work WHERE id = ?",
                String.class, first.workId()).contains("Backend Engineer"));
        assertTrue(run(vacancy, false, false).reused());
        assertFalse(run(vacancy, true, false).reused());
        verify(gateway, times(2)).extract(any(), anyString(), anyString());
        assertEquals(2, repository.budget().attempts());
        assertEquals(1, repository.budget().primaryDistinct());
        assertEquals(1, repository.budget().comparisonDistinct());
        assertEquals(2, repository.budget().succeeded());
        assertTrue(repository.budget().reservedMicroUsd() < ExtractionPolicy.TARGET_MICRO_USD);
    }

    @Test
    void previousPromptAndSchemaVersionCannotBeReusedAsCurrentWork() {
        long vacancy = insert();
        var input = inputs.load(vacancy);
        jdbc.update("INSERT INTO extraction_work (vacancy_id, source_item_id, vacancy_revision, provider, "
                + "model, prompt_version, schema_version, policy_version, input_json, kind, status, result_json) "
                + "VALUES (?, ?, ?, 'ANTHROPIC', ?, 'evidence-v1', 'schema-v1', 'pilot-v1', "
                + "CAST(? AS jsonb), 'PRIMARY', 'SUCCEEDED', '{\"claims\":[]}'::jsonb)",
                vacancy, input.sourceItemId(), input.revision(), ExtractionPolicy.PRIMARY_MODEL,
                json.writeValueAsString(input.sourceFields()));
        assertNull(service.current(vacancy, false));
        when(gateway.extract(any(), anyString(), anyString()))
                .thenReturn(new ExtractionGatewayResponseDTO(RESPONSE, 100, 50, "req-new-contract"));
        assertFalse(run(vacancy, false, false).reused());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM extraction_work", Integer.class));
        assertEquals(1, repository.budget().attempts());
    }

    @Test
    void estimatedProductionCostIncludesCompleteRequestAndMargin() {
        long vacancy = insert();
        ExtractionPilotService estimated = new ExtractionPilotService(inputs, validator, gateway, repository,
                json, manager, Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC),
                () -> "synthetic-key", new ConservativeExtractionCostEstimator(gateway),
                new ExtractionCancellationGuard());
        when(gateway.extract(any(), anyString(), anyString()))
                .thenReturn(new ExtractionGatewayResponseDTO(RESPONSE, 100, 50, "req-one"));
        estimated.extract(vacancy, false, false, true, "2026-10-29");
        assertEquals(ExtractionPolicy.cost(ExtractionPolicy.PRIMARY_MODEL, 8_392,
                ExtractionPolicy.MAX_OUTPUT_TOKENS), jdbc.queryForObject(
                "SELECT reserved_micro_usd FROM extraction_attempts", Long.class));
    }

    @Test
    void planningKeepsSelectedSalaryEvidenceAndRejectsMissingVacancy() {
        long vacancy = insert();
        jdbc.update("UPDATE source_items SET raw_payload = CAST(? AS jsonb)",
                "{\"workplaceType\":\"hybrid\",\"salary\":{\"min\":70000,\"currency\":\"EUR\"}}");
        var objectSalary = inputs.load(vacancy);
        assertEquals("hybrid", objectSalary.sourceFields().get("workplaceType"));
        assertTrue(objectSalary.sourceFields().get("salary").contains("EUR"));
        jdbc.update("UPDATE source_items SET raw_payload = CAST(? AS jsonb)",
                "{\"workplaceType\":17,\"salary\":\"70000 EUR yearly\"}");
        var stringSalary = inputs.load(vacancy);
        assertFalse(stringSalary.sourceFields().containsKey("workplaceType"));
        assertEquals("70000 EUR yearly", stringSalary.sourceFields().get("salary"));
        assertNotEquals(objectSalary.revision(), stringSalary.revision());
        assertThrows(IllegalArgumentException.class, () -> inputs.load(Long.MAX_VALUE));
        verify(gateway, never()).extract(any(), anyString(), anyString());
    }

    @Test
    void preInterruptedInvocationDoesNotReserveOrCallProvider() {
        long vacancy = insert();
        Thread.currentThread().interrupt();
        try {
            ExtractionFailureException failure = assertThrows(ExtractionFailureException.class,
                    () -> run(vacancy, false, false));
            assertEquals("CANCELLED", failure.outcome());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, repository.budget().attempts());
        verify(gateway, never()).extract(any(), anyString(), anyString());
    }

    @Test
    void cancellationAfterReservationRetainsBudgetWithoutSubmittingRequest() {
        long vacancy = insert();
        ExtractionCancellationGuard guard = new ExtractionCancellationGuard() {
            private int checks;

            @Override
            public void check() {
                if (++checks == 3) {
                    Thread.currentThread().interrupt();
                }
                super.check();
            }
        };
        ExtractionPilotService cancelled = new ExtractionPilotService(inputs, validator, gateway, repository,
                json, manager, Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC),
                () -> "synthetic-key", syntheticEstimator, guard);
        try {
            ExtractionFailureException failure = assertThrows(ExtractionFailureException.class,
                    () -> cancelled.extract(vacancy, false, false, true, "2026-10-29"));
            assertEquals("CANCELLED", failure.outcome());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, repository.budget().attempts());
        assertEquals(1, repository.budget().uncertain());
        assertEquals(syntheticReservation(ExtractionPolicy.PRIMARY_MODEL),
                repository.budget().reservedMicroUsd());
        verify(gateway, never()).extract(any(), anyString(), anyString());
    }

    @Test
    void comparisonRequiresPrimaryAndUncertainRequestRetainsReservation() {
        long vacancy = insert();
        assertThrows(IllegalStateException.class, () -> run(vacancy, true, false));
        assertEquals(0, repository.budget().attempts());
        when(gateway.extract(any(), anyString(), anyString()))
                .thenThrow(new ExtractionFailureException("UNCERTAIN", "Transport failed"));
        assertThrows(ExtractionFailureException.class, () -> run(vacancy, false, false));
        assertEquals(syntheticReservation(ExtractionPolicy.PRIMARY_MODEL),
                repository.budget().reservedMicroUsd());
        assertEquals(1, repository.budget().uncertain());
        assertThrows(IllegalStateException.class, () -> run(vacancy, false, false));
        assertThrows(IllegalStateException.class, () -> run(vacancy, false, true));
        recoverActive();
        assertThrows(ExtractionFailureException.class, () -> run(vacancy, false, true));
        assertEquals(2, repository.budget().attempts());
        verify(gateway, times(2)).extract(any(), anyString(), anyString());
    }

    @Test
    void changedSourceRevisionDoesNotReuseOldResult() {
        long vacancy = insert();
        when(gateway.extract(any(), anyString(), anyString()))
                .thenReturn(new ExtractionGatewayResponseDTO(RESPONSE, 100, 50, "req-one"));
        String revision = run(vacancy, false, false).revision();
        assertNotNull(service.current(vacancy, false));
        jdbc.update("UPDATE vacancies SET location = 'Berlin' WHERE id = ?", vacancy);
        assertNull(service.current(vacancy, false));
        assertNotEquals(revision, run(vacancy, false, false).revision());
        assertEquals(2, repository.budget().primaryDistinct());
    }

    @Test
    void concurrentClaimAllowsOnlyOneProviderCall() throws Exception {
        long vacancy = insert();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(gateway.extract(any(), anyString(), anyString())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("HTTP gate timed out");
            }
            return new ExtractionGatewayResponseDTO(RESPONSE, 100, 50, "req-one");
        });
        CompletableFuture<?> first = CompletableFuture.runAsync(() -> run(vacancy, false, false));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> run(vacancy, false, false));
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        verify(gateway, times(1)).extract(any(), anyString(), anyString());
        assertEquals(1, repository.budget().attempts());
    }

    @Test
    void concurrentReservationsCannotCrossDollarCap() throws Exception {
        long firstVacancy = insert();
        long secondVacancy = insertSecond();
        long amount = syntheticReservation(ExtractionPolicy.PRIMARY_MODEL);
        jdbc.update("UPDATE extraction_pilot_budget SET reserved_micro_usd = ? WHERE id = 1",
                ExtractionPolicy.TARGET_MICRO_USD - amount);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(gateway.extract(any(), anyString(), anyString())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("HTTP gate timed out");
            }
            return new ExtractionGatewayResponseDTO(RESPONSE, 100, 50, "req-one");
        });
        CompletableFuture<?> first = CompletableFuture.runAsync(() -> run(firstVacancy, false, false));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> run(secondVacancy, false, false));
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        assertEquals(1, repository.budget().attempts());
        verify(gateway, times(1)).extract(any(), anyString(), anyString());
    }

    @Test
    void failedAttemptInsertionRollsBackBudgetAndWork() {
        long vacancy = insert();
        jdbc.execute("ALTER TABLE extraction_attempts ADD CONSTRAINT extraction_test_reject "
                + "CHECK (reserved_micro_usd < 1)");
        try {
            assertThrows(RuntimeException.class, () -> run(vacancy, false, false));
            assertEquals(0, repository.budget().attempts());
            assertEquals(0, repository.budget().reservedMicroUsd());
            assertEquals(0, repository.budget().claimed());
            verify(gateway, never()).extract(any(), anyString(), anyString());
        } finally {
            jdbc.execute("ALTER TABLE extraction_attempts DROP CONSTRAINT extraction_test_reject");
        }
    }

    @Test
    void uncertainReplayIsBoundedAndBudgetSurvivesServiceRestart() {
        long vacancy = insert();
        when(gateway.extract(any(), anyString(), anyString()))
                .thenThrow(new ExtractionFailureException("UNCERTAIN", "Transport failed"));
        for (int index = 0; index < 3; index++) {
            boolean replay = index > 0;
            if (replay) {
                recoverActive();
            }
            assertThrows(ExtractionFailureException.class, () -> run(vacancy, false, replay));
        }
        ExtractionPilotService restarted = new ExtractionPilotService(inputs, validator, gateway, repository,
                json, manager, Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC),
                () -> "synthetic-key", syntheticEstimator, new ExtractionCancellationGuard());
        assertEquals(3, restarted.budget().attempts());
        assertEquals(3 * syntheticReservation(ExtractionPolicy.PRIMARY_MODEL),
                restarted.budget().reservedMicroUsd());
        assertThrows(IllegalStateException.class,
                () -> restarted.extract(vacancy, false, true, true, "2026-10-29"));
        verify(gateway, times(3)).extract(any(), anyString(), anyString());
    }

    @Test
    void capRejectsReservationBeforeHttp() {
        long vacancy = insert();
        jdbc.update("UPDATE extraction_pilot_budget SET reserved_micro_usd = ? WHERE id = 1",
                ExtractionPolicy.TARGET_MICRO_USD - syntheticReservation(ExtractionPolicy.PRIMARY_MODEL) + 1);
        assertThrows(IllegalStateException.class, () -> run(vacancy, false, false));
        assertEquals(0, repository.budget().attempts());
        verify(gateway, never()).extract(any(), anyString(), anyString());
    }

    @Test
    void globalClaimBlocksAnotherVacancyAfterLeaseExpiryUntilReviewedRecovery() {
        long first = insert();
        long second = insertSecond();
        when(gateway.extract(any(), anyString(), anyString()))
                .thenThrow(new ExtractionFailureException("UNCERTAIN", "Transport outcome unknown"));
        assertThrows(ExtractionFailureException.class, () -> run(first, false, false));
        jdbc.update("UPDATE extraction_pilot_budget SET active_since = CURRENT_TIMESTAMP - INTERVAL '61 seconds'");
        assertThrows(IllegalStateException.class, () -> run(second, false, false));
        assertEquals(1, repository.budget().attempts());
        verify(gateway, times(1)).extract(any(), anyString(), anyString());
        service.recoverGlobalClaim(repository.budget().activeWorkId(), true, true);
        assertThrows(ExtractionFailureException.class, () -> run(second, false, false));
        verify(gateway, times(2)).extract(any(), anyString(), anyString());
    }

    @Test
    void oversizedResponseRetainsReservationAndGlobalClaimUntilControlledRecovery() throws Exception {
        long first = insert();
        long second = insertSecond();
        AtomicInteger requests = new AtomicInteger();
        byte[] oversized = "x".repeat(ExtractionPolicy.MAX_RESPONSE_BYTES + 1)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, oversized.length);
            try (var response = exchange.getResponseBody()) {
                response.write(oversized);
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            AnthropicExtractionGateway localGateway = new AnthropicExtractionGateway(client,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages"), json);
            service = new ExtractionPilotService(inputs, validator, localGateway, repository, json, manager,
                    Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC),
                    () -> "synthetic-key", syntheticEstimator, new ExtractionCancellationGuard());
            ExtractionFailureException failure = assertThrows(ExtractionFailureException.class,
                    () -> run(first, false, false));
            assertEquals("UNCERTAIN", failure.outcome());
            long workId = repository.budget().activeWorkId();
            assertEquals(syntheticReservation(ExtractionPolicy.PRIMARY_MODEL),
                    repository.budget().reservedMicroUsd());
            assertEquals(0, repository.budget().observedMicroUsd());
            assertEquals(1, repository.budget().uncertain());
            assertEquals("UNCERTAIN", jdbc.queryForObject("SELECT outcome FROM extraction_attempts", String.class));
            assertThrows(IllegalStateException.class, () -> run(first, false, true));
            assertThrows(IllegalStateException.class, () -> run(second, false, false));
            assertEquals(1, requests.get());
            recoverActive();
            assertNull(repository.budget().activeWorkId());
            assertEquals(syntheticReservation(ExtractionPolicy.PRIMARY_MODEL),
                    repository.budget().reservedMicroUsd());
            assertThrows(IllegalStateException.class, () -> run(first, false, false));
            assertEquals(1, requests.get());
            assertThrows(ExtractionFailureException.class, () -> run(first, false, true));
            assertEquals(workId, repository.budget().activeWorkId());
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usageBeyondReservationIsRecordedAndHaltsPilot() {
        long vacancy = insert();
        when(gateway.extract(any(), anyString(), anyString()))
                .thenReturn(new ExtractionGatewayResponseDTO(RESPONSE, 8_000_000, 50, "req-large"));
        ExtractionFailureException failure = assertThrows(ExtractionFailureException.class,
                () -> run(vacancy, false, false));
        assertEquals("OVER_ESTIMATE", failure.outcome());
        long observed = ExtractionPolicy.cost(ExtractionPolicy.PRIMARY_MODEL, 8_000_000, 50);
        assertEquals(observed, repository.budget().observedMicroUsd());
        assertEquals(observed, repository.budget().reservedMicroUsd());
        assertTrue(observed > ExtractionPolicy.AUTHORIZED_CEILING_MICRO_USD);
        assertEquals("USAGE_OVER_RESERVATION", repository.budget().haltedReason());
        assertEquals(observed, jdbc.queryForObject("SELECT observed_cost_micro_usd FROM extraction_attempts",
                Long.class));
        recoverActive();
        assertThrows(IllegalStateException.class, () -> run(vacancy, false, true));
        verify(gateway, times(1)).extract(any(), anyString(), anyString());
    }

    @Test
    void recoveryRequiresElapsedLeaseAndExplicitBillingReview() {
        long vacancy = insert();
        when(gateway.extract(any(), anyString(), anyString()))
                .thenThrow(new ExtractionFailureException("UNCERTAIN", "Transport outcome unknown"));
        assertThrows(ExtractionFailureException.class, () -> run(vacancy, false, false));
        long active = repository.budget().activeWorkId();
        assertThrows(IllegalArgumentException.class, () -> service.recoverGlobalClaim(active, true, false));
        TransactionTemplate transaction = new TransactionTemplate(manager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            service.recoverGlobalClaim(active, true, true);
            return null;
        }));
        assertThrows(IllegalStateException.class, () -> service.recoverGlobalClaim(active, true, true));
        assertEquals(active, repository.budget().activeWorkId());
        assertThrows(IllegalStateException.class, () -> service.recoverGlobalClaim(active + 1, true, true));
        recoverActive();
        assertNull(repository.budget().activeWorkId());
        assertEquals(syntheticReservation(ExtractionPolicy.PRIMARY_MODEL),
                repository.budget().reservedMicroUsd());
    }

    @Test
    void distinctAndTotalAttemptLimitsRejectBeforeHttp() {
        long vacancy = insert();
        jdbc.update("UPDATE extraction_pilot_budget SET primary_distinct = 30 WHERE id = 1");
        assertThrows(IllegalStateException.class, () -> run(vacancy, false, false));
        jdbc.update("UPDATE extraction_pilot_budget SET primary_distinct = 0, attempts = 80 WHERE id = 1");
        assertThrows(IllegalStateException.class, () -> run(vacancy, false, false));
        jdbc.update("UPDATE extraction_pilot_budget SET attempts = 0 WHERE id = 1");
        when(gateway.extract(any(), eq(ExtractionPolicy.PRIMARY_MODEL), anyString()))
                .thenReturn(new ExtractionGatewayResponseDTO(RESPONSE, 100, 50, "req-primary"));
        run(vacancy, false, false);
        jdbc.update("UPDATE extraction_pilot_budget SET comparison_distinct = 10 WHERE id = 1");
        assertThrows(IllegalStateException.class, () -> run(vacancy, true, false));
        verify(gateway, times(1)).extract(any(), anyString(), anyString());
    }

    @Test
    void ambientTransactionIsRejectedWithoutReservation() {
        long vacancy = insert();
        TransactionTemplate transaction = new TransactionTemplate(manager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            run(vacancy, false, false);
            return null;
        }));
        assertEquals(0, repository.budget().attempts());
        verify(gateway, never()).extract(any(), anyString(), anyString());
    }

    @Test
    void staleClaimTokenCannotAcknowledgeReplayedWork() {
        long vacancy = insert();
        when(gateway.extract(any(), anyString(), anyString()))
                .thenThrow(new ExtractionFailureException("UNCERTAIN", "Transport failed"));
        assertThrows(ExtractionFailureException.class, () -> run(vacancy, false, false));
        UUID oldToken = jdbc.queryForObject("SELECT claim_token FROM extraction_attempts ORDER BY id LIMIT 1",
                UUID.class);
        long workId = jdbc.queryForObject("SELECT id FROM extraction_work", Long.class);
        recoverActive();
        assertThrows(ExtractionFailureException.class, () -> run(vacancy, false, true));
        TransactionTemplate transaction = new TransactionTemplate(manager);
        ExtractionCompletionDTO completion = completion(workId, oldToken);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            repository.finish(completion);
            return null;
        }));
        assertEquals("UNCERTAIN", jdbc.queryForObject("SELECT status FROM extraction_work WHERE id = ?",
                String.class, workId));
    }

    @Test
    void expiredClaimBecomesUncertainWithoutAutomaticRequest() {
        long vacancy = insert();
        var input = inputs.load(vacancy);
        UUID oldToken = UUID.randomUUID();
        var identity = new ExtractionWorkIdentityDTO(vacancy, input.sourceItemId(), input.revision(),
                ExtractionPolicy.PRIMARY_MODEL, ExtractionPolicy.PROMPT_VERSION,
                ExtractionPolicy.SCHEMA_VERSION, ExtractionPolicy.POLICY_VERSION);
        long workId = repository.insertWork(identity, "PRIMARY", json.writeValueAsString(input.sourceFields()), oldToken);
        repository.reserve(workId, oldToken, syntheticReservation(ExtractionPolicy.PRIMARY_MODEL),
                true, true);
        jdbc.update("UPDATE extraction_work SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                + "WHERE id = ?", workId);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        ExtractionCompletionDTO completion = completion(workId, oldToken);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            repository.finish(completion);
            return null;
        }));
        assertThrows(IllegalStateException.class, () -> run(vacancy, false, false));
        assertEquals("CLAIMED", jdbc.queryForObject("SELECT status FROM extraction_work WHERE id = ?",
                String.class, workId));
        assertEquals(workId, repository.budget().activeWorkId());
        assertThrows(IllegalArgumentException.class,
                () -> service.recoverGlobalClaim(workId, false, true));
        recoverActive();
        assertEquals("UNCERTAIN", jdbc.queryForObject("SELECT status FROM extraction_work WHERE id = ?",
                String.class, workId));
        assertEquals(1, repository.budget().attempts());
        verify(gateway, never()).extract(any(), anyString(), anyString());
    }

    @Test
    void trackingFailureDoesNotReplaceOriginalTransportFailure() {
        long vacancy = insert();
        jdbc.execute("ALTER TABLE extraction_work ADD CONSTRAINT extraction_test_reject_uncertain "
                + "CHECK (status <> 'UNCERTAIN')");
        try {
            when(gateway.extract(any(), anyString(), anyString()))
                    .thenThrow(new ExtractionFailureException("UNCERTAIN", "Transport failed"));
            ExtractionFailureException original = assertThrows(ExtractionFailureException.class,
                    () -> run(vacancy, false, false));
            assertEquals("Transport failed", original.getMessage());
            assertEquals(1, original.getSuppressed().length);
            assertEquals(1, repository.budget().attempts());
            assertEquals(1, repository.budget().claimed());
        } finally {
            jdbc.execute("ALTER TABLE extraction_work DROP CONSTRAINT extraction_test_reject_uncertain");
        }
    }

    @Test
    void refusalUsageIsRecordedAndOnlyEvidenceBackedUnusedBudgetIsReleased() {
        long vacancy = insert();
        when(gateway.extract(any(), anyString(), anyString()))
                .thenThrow(new ExtractionFailureException("INVALID", "Incomplete provider response",
                        120, 30, "req-refusal"));
        assertThrows(ExtractionFailureException.class, () -> run(vacancy, false, false));
        long actual = ExtractionPolicy.cost(ExtractionPolicy.PRIMARY_MODEL, 120, 30);
        assertEquals(actual, repository.budget().reservedMicroUsd());
        assertEquals(actual, jdbc.queryForObject("SELECT observed_cost_micro_usd FROM extraction_attempts", Long.class));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM extraction_work", String.class));
    }

    @Test
    void invalidClaimsWithReportedUsageAreChargedAndDoNotBlockOtherWork() {
        long first = insert();
        long second = insertSecond();
        String rejected = """
                        {"truncated":false,"claims":[{"fact":"TITLE","value":"Invented title","sourceField":"title",
                        "quote":"Invented title","uncertainty":"STATED"}]}
                        """;
        when(gateway.extract(any(), anyString(), anyString()))
                .thenReturn(new ExtractionGatewayResponseDTO(rejected, 120, 30, "req-invalid"))
                .thenReturn(new ExtractionGatewayResponseDTO(RESPONSE, 100, 50, "req-valid"));
        ExtractionValidationException failure = assertThrows(ExtractionValidationException.class,
                () -> run(first, false, false));
        assertEquals(0, failure.claimIndex());
        assertEquals("QUOTE_NOT_IN_SOURCE_FIELD", failure.category());
        assertEquals(rejected, jdbc.queryForObject("SELECT rejected_output FROM extraction_attempts WHERE work_id = 1",
                String.class));
        assertEquals("QUOTE_NOT_IN_SOURCE_FIELD", jdbc.queryForObject(
                "SELECT validation_category FROM extraction_attempts WHERE work_id = 1", String.class));
        assertNull(jdbc.queryForObject("SELECT result_json::text FROM extraction_work WHERE id = 1",
                String.class));
        long observed = ExtractionPolicy.cost(ExtractionPolicy.PRIMARY_MODEL, 120, 30);
        assertEquals(observed, repository.budget().observedMicroUsd());
        assertEquals(observed, repository.budget().reservedMicroUsd());
        assertNull(repository.budget().activeWorkId());
        assertEquals(1, repository.budget().failed());
        assertFalse(run(second, false, false).reused());
        assertEquals(2, repository.budget().attempts());
        verify(gateway, times(2)).extract(any(), anyString(), anyString());
    }

    @Test
    void oversizedRejectedArtifactStoresOnlyBoundedDiagnostic() {
        long vacancy = insert();
        String rejected = "x".repeat(17_000);
        when(gateway.extract(any(), anyString(), anyString()))
                .thenReturn(new ExtractionGatewayResponseDTO(rejected, 120, 30, "req-invalid"));
        ExtractionValidationException failure = assertThrows(ExtractionValidationException.class,
                () -> run(vacancy, false, false));
        assertEquals("INVALID_JSON", failure.category());
        assertNull(jdbc.queryForObject("SELECT rejected_output FROM extraction_attempts", String.class));
        assertEquals(-1, jdbc.queryForObject("SELECT validation_claim_index FROM extraction_attempts",
                Integer.class));
        assertEquals(1, repository.budget().failed());
        assertEquals(1, repository.budget().attempts());
    }

    @Test
    void changedVacancyBetweenPlanningAndReservationCannotSpendOrSend() {
        long vacancy = insert();
        ExtractionCostEstimator changingEstimator = (input, model) -> {
            jdbc.update("UPDATE vacancies SET location = 'Berlin' WHERE id = ?", vacancy);
            return syntheticReservation(model);
        };
        ExtractionPilotService changed = new ExtractionPilotService(inputs, validator, gateway, repository,
                json, manager, Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC),
                () -> "synthetic-key", changingEstimator, new ExtractionCancellationGuard());
        assertThrows(IllegalStateException.class,
                () -> changed.extract(vacancy, false, false, true, "2026-10-29"));
        assertEquals(0, repository.budget().attempts());
        verify(gateway, never()).extract(any(), anyString(), anyString());
    }

    @Test
    void invalidCostEstimateAndMissingCredentialStopBeforeReservation() {
        long vacancy = insert();
        for (long estimate : new long[] {0, ExtractionPolicy.TARGET_MICRO_USD + 1}) {
            ExtractionPilotService refused = new ExtractionPilotService(inputs, validator, gateway, repository,
                    json, manager, Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC),
                    () -> "synthetic-key", (input, model) -> estimate, new ExtractionCancellationGuard());
            assertThrows(IllegalStateException.class,
                    () -> refused.extract(vacancy, false, false, true, "2026-10-29"));
        }
        ExtractionPilotService noCredential = new ExtractionPilotService(inputs, validator, gateway, repository,
                json, manager, Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC),
                () -> null, syntheticEstimator, new ExtractionCancellationGuard());
        assertThrows(IllegalStateException.class,
                () -> noCredential.extract(vacancy, false, false, true, "2026-10-29"));
        assertEquals(0, repository.budget().attempts());
        verify(gateway, never()).extract(any(), anyString(), anyString());
    }

    @Test
    void lostSuccessAcknowledgementLeavesClaimUncertainForRecovery() {
        long vacancy = insert();
        jdbc.execute("ALTER TABLE extraction_work ADD CONSTRAINT extraction_test_reject_success "
                + "CHECK (status <> 'SUCCEEDED')");
        try {
            when(gateway.extract(any(), anyString(), anyString()))
                    .thenReturn(new ExtractionGatewayResponseDTO(RESPONSE, 100, 50, "req-one"));
            assertThrows(RuntimeException.class, () -> run(vacancy, false, false));
            assertEquals("CLAIMED", jdbc.queryForObject("SELECT status FROM extraction_work", String.class));
            assertEquals("RESERVED", jdbc.queryForObject("SELECT outcome FROM extraction_attempts", String.class));
            assertEquals(syntheticReservation(ExtractionPolicy.PRIMARY_MODEL),
                    repository.budget().reservedMicroUsd());
        } finally {
            jdbc.execute("ALTER TABLE extraction_work DROP CONSTRAINT extraction_test_reject_success");
        }
    }

    private com.alexs.jobagent.evaluation.dto.ExtractionResultDTO run(long vacancy, boolean comparison,
                                                                          boolean replay) {
        return service.extract(vacancy, comparison, replay, true, "2026-10-29");
    }

    private static ExtractionCompletionDTO completion(long workId, UUID token) {
        return new ExtractionCompletionDTO(workId, token, "SUCCEEDED", "{\"truncated\":false,\"claims\":[]}", "SUCCEEDED",
                new ExtractionUsageDTO(1, 0, 0, 1, 1L, null),
                syntheticReservation(ExtractionPolicy.PRIMARY_MODEL));
    }

    private void recoverActive() {
        long workId = repository.budget().activeWorkId();
        jdbc.update("UPDATE extraction_pilot_budget SET active_since = CURRENT_TIMESTAMP - INTERVAL '61 seconds' "
                + "WHERE id = 1");
        service.recoverGlobalClaim(workId, true, true);
    }

    private long insert() {
        long employer = jdbc.queryForObject("INSERT INTO employers(source_namespace, external_id, name) "
                + "VALUES ('synthetic-board', 'employer-1', 'Fictional Works') RETURNING id", Long.class);
        long vacancy = jdbc.queryForObject("INSERT INTO vacancies(employer_id, source_namespace, listing_id, title, "
                + "location) VALUES (?, 'synthetic-board', 'listing-1', 'Backend Engineer', 'Remote') RETURNING id",
                Long.class, employer);
        long source = jdbc.queryForObject("INSERT INTO source_items(source_namespace, source_item_id, "
                + "listing_namespace, listing_id, original_url, fetched_at, raw_payload, input_version, "
                + "normalization_version, input_digest) VALUES ('synthetic-board', 'snapshot-1', 'synthetic-board', "
                + "'listing-1', 'https://jobs.example/1', CURRENT_TIMESTAMP, '{\"workplaceType\":\"remote\"}'::jsonb, "
                + "'synthetic-v1', 'rules-v1', repeat('a',64)) RETURNING id", Long.class);
        jdbc.update("INSERT INTO vacancy_sources(vacancy_id, source_item_id) VALUES (?, ?)", vacancy, source);
        return vacancy;
    }

    private long insertSecond() {
        long employer = jdbc.queryForObject("INSERT INTO employers(source_namespace, external_id, name) "
                + "VALUES ('synthetic-board', 'employer-2', 'Fictional Works 2') RETURNING id", Long.class);
        long vacancy = jdbc.queryForObject("INSERT INTO vacancies(employer_id, source_namespace, listing_id, title, "
                + "location) VALUES (?, 'synthetic-board', 'listing-2', 'Backend Engineer', 'Remote') RETURNING id",
                Long.class, employer);
        long source = jdbc.queryForObject("INSERT INTO source_items(source_namespace, source_item_id, "
                + "listing_namespace, listing_id, original_url, fetched_at, raw_payload, input_version, "
                + "normalization_version, input_digest) VALUES ('synthetic-board', 'snapshot-2', 'synthetic-board', "
                + "'listing-2', 'https://jobs.example/2', CURRENT_TIMESTAMP, '{\"workplaceType\":\"remote\"}'::jsonb, "
                + "'synthetic-v1', 'rules-v1', repeat('b',64)) RETURNING id", Long.class);
        jdbc.update("INSERT INTO vacancy_sources(vacancy_id, source_item_id) VALUES (?, ?)", vacancy, source);
        return vacancy;
    }
}
