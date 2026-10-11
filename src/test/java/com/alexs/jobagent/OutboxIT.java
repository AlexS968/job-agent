package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.outbox.entity.OutboxEventEntity;
import com.alexs.jobagent.outbox.service.LocalEventDispatcher;
import com.alexs.jobagent.outbox.service.OutboxService;
import com.alexs.jobagent.outbox.service.SyntheticVacancyConsumer;
import com.alexs.jobagent.source.dto.SyntheticInputDTO;
import com.alexs.jobagent.source.service.SyntheticIngestionService;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(OutboxIT.TestClockConfig.class)
class OutboxIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("job_agent_outbox_test").withUsername("test_user")
            .withPassword("synthetic_test_secret_123");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired SyntheticIngestionService ingestion;
    @Autowired OutboxService outbox;
    @Autowired LocalEventDispatcher dispatcher;
    @Autowired SyntheticVacancyConsumer consumer;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired MutableClock clock;

    @BeforeEach
    void clear() {
        clock.reset();
        jdbc.execute("TRUNCATE synthetic_vacancy_observations, consumer_deliveries, outbox_events, "
                + "vacancy_sources, source_items, vacancies, employers, agent_runs RESTART IDENTITY CASCADE");
    }

    @Test
    void migrationFromEmptyDatabaseAndAtomicEmission() throws Exception {
        assertEquals(3, count("flyway_schema_history"));
        ingestion.ingest(fixture());
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("outbox_events"));
        assertEquals("VacancyDiscovered", jdbc.queryForObject("SELECT event_type FROM outbox_events", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT schema_version FROM outbox_events", Integer.class));
        assertEquals("PENDING", state());
    }

    @Test
    void replayAndSecondInputForExistingVacancyDoNotEmitAgain() throws Exception {
        SyntheticInputDTO first = fixture();
        ingestion.ingest(first);
        ingestion.ingest(first);
        ingestion.ingest(new SyntheticInputDTO(first.sourceNamespace(), "snapshot-002", first.originalUrl(),
                first.fetchedAt(), first.rawPayload(), first.inputVersion(), first.normalizationVersion(), first.vacancy()));
        assertEquals(1, count("vacancies"));
        assertEquals(1, count("outbox_events"));
        assertEquals(2, count("source_items"));
    }

    @Test
    void failureAfterOutboxInsertRollsBackDomainAndEvent() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_success() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic test failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_success BEFORE UPDATE ON agent_runs "
                + "FOR EACH ROW WHEN (NEW.status = 'SUCCEEDED') EXECUTE FUNCTION reject_success()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> ingestion.ingest(fixture()));
            assertEquals(0, count("vacancies"));
            assertEquals(0, count("outbox_events"));
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs", String.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_success ON agent_runs");
            jdbc.execute("DROP FUNCTION reject_success()");
        }
    }

    @Test
    void successfulDeliveryAndRedeliveryHaveOneEffect() throws Exception {
        ingestion.ingest(fixture());
        OutboxEventEntity claimed = outbox.claim("worker-a");
        assertNotNull(claimed);
        consumer.handle(claimed);
        consumer.handle(claimed);
        assertEquals(1, count("consumer_deliveries"));
        assertEquals(1, count("synthetic_vacancy_observations"));
        assertTrue(outbox.complete(claimed));
        assertEquals("COMPLETED", state());
        assertNull(outbox.claim("worker-b"));
    }

    @Test
    void crashAfterConsumerCommitBeforeAckRecoversWithNoDuplicateEffect() throws Exception {
        ingestion.ingest(fixture());
        OutboxEventEntity abandoned = outbox.claim("crashed-worker");
        consumer.handle(abandoned);
        expireLease();
        var result = dispatcher.dispatch(20);
        assertEquals(1, result.completed());
        assertEquals(1, count("consumer_deliveries"));
        assertEquals(1, count("synthetic_vacancy_observations"));
        assertFalse(outbox.complete(abandoned));
        assertEquals(2, jdbc.queryForObject("SELECT total_attempts FROM outbox_events", Integer.class));
    }

    @Test
    void repeatedCrashesExhaustLeaseClaimsWithoutFourthAttempt() throws Exception {
        ingestion.ingest(fixture());
        OutboxEventEntity first = outbox.claim("crash-one");
        assertEquals(1, first.cycleAttempts());
        expireLease();
        OutboxEventEntity second = outbox.claim("crash-two");
        assertEquals(2, second.cycleAttempts());
        expireLease();
        OutboxEventEntity third = outbox.claim("crash-three");
        assertEquals(3, third.cycleAttempts());
        expireLease();
        CountDownLatch recover = new CountDownLatch(1);
        var recoveryOne = CompletableFuture.supplyAsync(() -> awaitRecovery(recover, "fourth-worker"));
        var recoveryTwo = CompletableFuture.supplyAsync(() -> awaitRecovery(recover, "fifth-worker"));
        recover.countDown();
        var exhaustedOne = recoveryOne.get(10, TimeUnit.SECONDS);
        var exhaustedTwo = recoveryTwo.get(10, TimeUnit.SECONDS);
        assertNull(exhaustedOne.event());
        assertNull(exhaustedTwo.event());
        assertEquals(1, exhaustedOne.exhaustedClaims() + exhaustedTwo.exhaustedClaims());
        assertEquals("FAILED", state());
        assertEquals("LEASE_EXHAUSTED", jdbc.queryForObject("SELECT failure_code FROM outbox_events", String.class));
        assertEquals(3, jdbc.queryForObject("SELECT total_attempts FROM outbox_events", Integer.class));
        assertFalse(outbox.complete(first));
        assertFalse(outbox.deferOrFail(third, "HANDLER_ERROR"));
        assertEquals(1, outbox.backlog().terminalFailures());
        assertTrue(outbox.replayFailed(eventId()));
        assertFalse(outbox.complete(third));
        assertFalse(outbox.deferOrFail(second, "HANDLER_ERROR"));
        assertEquals(1, dispatcher.dispatch(20).completed());
        assertEquals(4, jdbc.queryForObject("SELECT total_attempts FROM outbox_events", Integer.class));
        assertEquals(1, count("consumer_deliveries"));
    }

    @Test
    void finalConsumerCommitWithLostAcknowledgementIsReconciledByReplay() throws Exception {
        ingestion.ingest(fixture());
        OutboxEventEntity first = outbox.claim("crash-one");
        expireLease();
        OutboxEventEntity second = outbox.claim("crash-two");
        expireLease();
        OutboxEventEntity third = outbox.claim("final-worker");
        consumer.handle(third);
        assertEquals(1, count("consumer_deliveries"));
        assertEquals(1, count("synthetic_vacancy_observations"));
        expireLease();
        var result = dispatcher.dispatch(20);
        assertEquals(1, result.failed());
        assertEquals(0, result.completed());
        assertEquals(1, result.backlog().terminalFailures());
        assertEquals("FAILED", state());
        assertFalse(outbox.complete(first));
        assertFalse(outbox.complete(third));
        assertTrue(outbox.replayFailed(eventId()));
        assertEquals(1, dispatcher.dispatch(20).completed());
        assertEquals(1, count("consumer_deliveries"));
        assertEquals(1, count("synthetic_vacancy_observations"));
    }

    @Test
    void futureRetryRemainsVisibleUntilClockMakesItEligible() throws Exception {
        ingestion.ingest(fixture());
        jdbc.update("UPDATE outbox_events SET event_type = 'Unsupported'");
        var first = dispatcher.dispatch(20);
        assertEquals(1, first.deferred());
        assertEquals(1, first.backlog().futureRetries());
        assertEquals(0, first.backlog().ready());
        var beforeDue = dispatcher.dispatch(20);
        assertEquals(0, beforeDue.completed());
        assertEquals(0, beforeDue.deferred());
        assertEquals(0, beforeDue.failed());
        assertEquals(1, beforeDue.backlog().futureRetries());
        assertEquals(1, jdbc.queryForObject("SELECT cycle_attempts FROM outbox_events", Integer.class));
        clock.advance(Duration.ofSeconds(1));
        var afterDue = dispatcher.dispatch(20);
        assertEquals(1, afterDue.deferred());
        assertEquals(2, jdbc.queryForObject("SELECT cycle_attempts FROM outbox_events", Integer.class));
        assertEquals(1, afterDue.backlog().futureRetries());
    }

    @Test
    void competingWorkersGetOnlyOneClaimAndStaleTokenCannotMutateNewClaim() throws Exception {
        ingestion.ingest(fixture());
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<OutboxEventEntity> first = CompletableFuture.supplyAsync(() -> awaitClaim(start, "one"));
        CompletableFuture<OutboxEventEntity> second = CompletableFuture.supplyAsync(() -> awaitClaim(start, "two"));
        start.countDown();
        OutboxEventEntity one = first.get(10, TimeUnit.SECONDS);
        OutboxEventEntity two = second.get(10, TimeUnit.SECONDS);
        assertTrue((one == null) != (two == null));
        OutboxEventEntity old = one == null ? two : one;
        expireLease();
        OutboxEventEntity newer = outbox.claim("new-owner");
        assertNotNull(newer);
        assertNotEquals(old.claimToken(), newer.claimToken());
        assertFalse(outbox.complete(old));
        assertFalse(outbox.deferOrFail(old, "HANDLER_ERROR"));
        assertTrue(outbox.complete(newer));
    }

    @Test
    void handlerFailureRetriesThenFailsAndControlledReplaySucceeds() throws Exception {
        ingestion.ingest(fixture());
        jdbc.update("UPDATE outbox_events SET event_type = 'Unsupported'");
        for (int attempt = 1; attempt <= 3; attempt++) {
            var result = dispatcher.dispatch(20);
            assertEquals(attempt == 3 ? 1 : 0, result.failed());
            assertEquals(attempt == 3 ? "FAILED" : "PENDING", state());
            if (attempt < 3) {
                assertEquals(1, result.deferred());
                clock.advance(Duration.ofSeconds(1L << (attempt - 1)));
            }
        }
        assertEquals(1, count("vacancies"));
        assertEquals(0, count("consumer_deliveries"));
        assertEquals("UNSUPPORTED_CONTRACT", jdbc.queryForObject("SELECT failure_code FROM outbox_events", String.class));
        UUID id = eventId();
        assertTrue(outbox.replayFailed(id));
        assertFalse(outbox.replayFailed(id));
        jdbc.update("UPDATE outbox_events SET event_type = 'VacancyDiscovered'");
        assertEquals(1, dispatcher.dispatch(20).completed());
        assertEquals(4, jdbc.queryForObject("SELECT total_attempts FROM outbox_events", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT cycle_attempts FROM outbox_events", Integer.class));
        assertEquals(1, count("synthetic_vacancy_observations"));
    }

    @Test
    void unsupportedVersionIsRejectedWithoutConsumerEffect() throws Exception {
        ingestion.ingest(fixture());
        jdbc.update("UPDATE outbox_events SET schema_version = 2");
        assertEquals(1, dispatcher.dispatch(20).deferred());
        assertEquals(0, count("consumer_deliveries"));
        assertEquals("PENDING", state());
    }

    @Test
    void handlerDatabaseFailureRollsBackReceiptAndKeepsCommittedVacancy() throws Exception {
        ingestion.ingest(fixture());
        jdbc.execute("CREATE FUNCTION reject_observation() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic test failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_observation BEFORE INSERT ON synthetic_vacancy_observations "
                + "FOR EACH ROW EXECUTE FUNCTION reject_observation()");
        try {
            assertEquals(1, dispatcher.dispatch(20).deferred());
            assertEquals(1, count("vacancies"));
            assertEquals(1, count("outbox_events"));
            assertEquals(0, count("consumer_deliveries"));
            assertEquals(0, count("synthetic_vacancy_observations"));
            assertEquals("HANDLER_ERROR", jdbc.queryForObject("SELECT failure_code FROM outbox_events", String.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_observation ON synthetic_vacancy_observations");
            jdbc.execute("DROP FUNCTION reject_observation()");
        }
    }

    @Test
    void uncommittedEventCannotBeClaimedAndAmbientDispatchIsRejected() throws Exception {
        ingestion.ingest(fixture());
        dispatcher.dispatch(20);
        var tx = new TransactionTemplate(manager);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO outbox_events(event_id, event_type, schema_version, aggregate_id, "
                            + "occurred_at, run_id, payload, next_attempt_at) "
                            + "SELECT ?, 'VacancyDiscovered', 1, v.id, ?, r.id, '{}'::jsonb, ? "
                            + "FROM vacancies v CROSS JOIN agent_runs r LIMIT 1",
                    UUID.randomUUID(), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
            inserted.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }));
        assertTrue(inserted.await(10, TimeUnit.SECONDS));
        try {
            assertNull(outbox.claim("reader"));
            assertThrows(IllegalStateException.class, () -> tx.execute(status -> outbox.claim("ambient")));
        } finally {
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
        }
        assertNotNull(outbox.claim("reader"));
    }

    private OutboxEventEntity awaitClaim(CountDownLatch start, String worker) {
        try { start.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        return outbox.claim(worker);
    }

    private com.alexs.jobagent.outbox.dto.ClaimResultDTO awaitRecovery(CountDownLatch start, String worker) {
        try { start.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        return outbox.claimWithRecovery(worker);
    }

    private void expireLease() {
        clock.advance(Duration.ofMinutes(3));
    }

    private UUID eventId() { return jdbc.queryForObject("SELECT event_id FROM outbox_events", UUID.class); }
    private String state() { return jdbc.queryForObject("SELECT state FROM outbox_events", String.class); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private SyntheticInputDTO fixture() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/synthetic/source-item.json")) {
            assertNotNull(stream);
            return json.readValue(stream, SyntheticInputDTO.class);
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private static final Instant START = Instant.parse("2026-10-11T00:00:00Z");
        private final AtomicReference<Instant> current = new AtomicReference<>(START);

        void reset() { current.set(START); }
        void advance(Duration duration) { current.updateAndGet(instant -> instant.plus(duration)); }
        @Override public Instant instant() { return current.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
    }
}
