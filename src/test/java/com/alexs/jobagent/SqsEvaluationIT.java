package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.SearchProfileDTO;
import com.alexs.jobagent.evaluation.service.EvaluationService;
import com.alexs.jobagent.evaluation.service.ProfileFileService;
import com.alexs.jobagent.outbox.dto.SqsMessageDTO;
import com.alexs.jobagent.outbox.service.OutboxService;
import com.alexs.jobagent.outbox.service.SqsEvaluationConsumerService;
import com.alexs.jobagent.outbox.service.SqsGateway;
import com.alexs.jobagent.outbox.service.SqsProducerService;
import com.alexs.jobagent.outbox.repository.ConsumerDeliveryRepository;
import com.alexs.jobagent.source.dto.SyntheticInputDTO;
import com.alexs.jobagent.source.service.SyntheticIngestionService;
import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
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
class SqsEvaluationIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("job_agent_sqs_eval_test").withUsername("test_user")
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
    @Autowired EvaluationService evaluations;
    @Autowired ProfileFileService profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager manager;

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE sqs_evaluation_effects, sqs_vacancy_observations, vacancy_evaluations, "
                + "evaluation_profiles, consumer_deliveries, outbox_events, vacancy_sources, source_items, "
                + "vacancies, employers, agent_runs RESTART IDENTITY CASCADE");
    }

    @Test
    void evaluatedEventPersistsExplanationAndDuplicateDoesNotRepeatIt() throws Exception {
        Queue queue = published();
        var consumer = consumer(queue);
        var profile = profile("v1");
        assertEquals(1, consumer.consume(1, profile).evaluated());
        assertEquals("EVALUATED", jdbc.queryForObject("SELECT outcome FROM sqs_evaluation_effects", String.class));
        assertEquals(1, count("vacancy_evaluations"));
        assertEquals(1, count("consumer_deliveries"));
        assertNotNull(jdbc.queryForObject("SELECT evaluation_id FROM sqs_evaluation_effects", Long.class));
        assertTrue(jdbc.queryForObject("SELECT reasons::text FROM vacancy_evaluations", String.class).contains("ROLE"));
        var effect = consumer.status(eventId(), profile);
        assertEquals("EVALUATED", effect.outcome());
        assertEquals("rules-v1", effect.evaluation().policyVersion());
        assertEquals(profile.criteriaHash(), effect.evaluation().criteriaHash());
        assertFalse(effect.evaluation().reasons().isEmpty());
        queue.body = queue.sent;
        assertEquals(1, consumer.consume(1, profile).duplicates());
        assertEquals(1, count("vacancy_evaluations"));
        assertEquals(1, count("sqs_evaluation_effects"));
    }

    @Test
    void concurrentDeliveryAndLostDeleteAreDeduplicated() throws Exception {
        Queue queue = published();
        queue.failDelete = true;
        var first = consumer(queue);
        var second = consumer(queue);
        var profile = profile("v1");
        assertEquals(1, first.consume(1, profile).failed());
        assertEquals(1, count("vacancy_evaluations"));
        queue.failDelete = false;
        CountDownLatch start = new CountDownLatch(1);
        var a = CompletableFuture.supplyAsync(() -> after(start, first, profile));
        var b = CompletableFuture.supplyAsync(() -> after(start, second, profile));
        start.countDown();
        assertEquals(0, a.get(10, TimeUnit.SECONDS).failed());
        assertEquals(0, b.get(10, TimeUnit.SECONDS).failed());
        assertEquals(1, count("vacancy_evaluations"));
        assertEquals(1, count("consumer_deliveries"));
    }

    @Test
    void simultaneousFirstDeliveryCommitsOnlyOneEvaluation() throws Exception {
        Queue queue = published();
        queue.receiveBarrier = new CountDownLatch(2);
        var profile = profile("v1");
        var first = consumer(queue);
        var second = consumer(queue);
        CountDownLatch start = new CountDownLatch(1);
        var a = CompletableFuture.supplyAsync(() -> after(start, first, profile));
        var b = CompletableFuture.supplyAsync(() -> after(start, second, profile));
        start.countDown();
        var resultA = a.get(10, TimeUnit.SECONDS);
        var resultB = b.get(10, TimeUnit.SECONDS);
        assertEquals(1, resultA.evaluated() + resultB.evaluated());
        assertEquals(1, resultA.duplicates() + resultB.duplicates());
        assertEquals(0, resultA.failed() + resultB.failed());
        assertEquals(1, count("vacancy_evaluations"));
        assertEquals(1, count("consumer_deliveries"));
    }

    @Test
    void failedEvaluationRollsBackReceiptAndCanRetry() throws Exception {
        Queue queue = published();
        var consumer = consumer(queue);
        var profile = profile("v1");
        jdbc.execute("CREATE FUNCTION reject_sqs_evaluation() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_sqs_evaluation BEFORE INSERT ON vacancy_evaluations "
                + "FOR EACH ROW EXECUTE FUNCTION reject_sqs_evaluation()");
        try {
            assertEquals(1, consumer.consume(1, profile).failed());
            assertEquals(0, count("consumer_deliveries"));
            assertEquals(0, count("evaluation_profiles"));
            assertEquals(0, count("sqs_evaluation_effects"));
            assertNotNull(queue.body);
        } finally {
            jdbc.execute("DROP TRIGGER reject_sqs_evaluation ON vacancy_evaluations");
            jdbc.execute("DROP FUNCTION reject_sqs_evaluation()");
        }
        assertEquals(1, consumer.consume(1, profile).evaluated());
    }

    @Test
    void staleRevisionSkipsEvaluationAndNewProfileVersionHasSeparateReceipt() throws Exception {
        Queue queue = published();
        var consumer = consumer(queue);
        var first = profile("v1");
        jdbc.update("UPDATE vacancies SET title = 'Changed title' WHERE id = 1");
        assertEquals(1, consumer.consume(1, first).stale());
        assertEquals("STALE", jdbc.queryForObject("SELECT outcome FROM sqs_evaluation_effects", String.class));
        assertEquals(0, count("vacancy_evaluations"));
        assertEquals("STALE", consumer.status(eventId(), first).outcome());
        assertNull(consumer.status(eventId(), first).evaluation());
        queue.body = queue.sent;
        assertEquals(1, consumer.consume(1, first).duplicates());
        queue.body = queue.sent;
        assertEquals(1, consumer.consume(1, profile("v2")).stale());
        assertEquals(2, count("consumer_deliveries"));
        var newEvent = new Queue();
        new TransactionTemplate(manager).executeWithoutResult(status -> outbox.vacancyUpdated(1, 1));
        assertEquals(1, new SqsProducerService(outbox, newEvent, json).publish(1).processed());
        assertEquals(1, consumer(newEvent).consume(1, first).evaluated());
        assertEquals(1, count("vacancy_evaluations"));
        assertNotEquals(json.readValue(queue.sent, com.alexs.jobagent.outbox.dto.SqsEventDTO.class).eventId(),
                json.readValue(newEvent.sent, com.alexs.jobagent.outbox.dto.SqsEventDTO.class).eventId());
    }

    @Test
    void differentProfileVersionEvaluatesSameCurrentRevisionSeparately() throws Exception {
        Queue queue = published();
        var consumer = consumer(queue);
        assertEquals(1, consumer.consume(1, profile("v1")).evaluated());
        queue.body = queue.sent;
        assertEquals(1, consumer.consume(1, profile("v2")).evaluated());
        assertEquals(2, count("vacancy_evaluations"));
        assertEquals(2, count("consumer_deliveries"));
    }

    @Test
    void unsupportedHistoricalAndInvalidEnvelopeAreNeverAcknowledged() throws Exception {
        Queue queue = published();
        var consumer = consumer(queue);
        var profile = profile("v1");
        String valid = queue.body;
        queue.body = valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        assertEquals(1, consumer.consume(1, profile).failed());
        queue.body = "not-json";
        assertEquals(1, consumer.consume(1, profile).failed());
        queue.body = valid;
        jdbc.update("UPDATE outbox_events SET payload = '{\"vacancy_id\":1}'::jsonb");
        assertEquals(1, consumer.consume(1, profile).failed());
        assertEquals(0, count("consumer_deliveries"));
        assertEquals(0, count("vacancy_evaluations"));
    }

    @Test
    void missingProvenanceAndInvalidProfileNeverAcknowledgeEvent() throws Exception {
        Queue queue = published();
        var consumer = consumer(queue);
        var profile = profile("v1");
        assertThrows(IllegalArgumentException.class, () -> consumer.consume(1, null));
        assertThrows(IllegalArgumentException.class, () -> consumer.consume(0, profile));
        assertThrows(IllegalArgumentException.class, () -> consumer.status(UUID.randomUUID(), profile));
        jdbc.update("DELETE FROM vacancy_sources WHERE vacancy_id = 1");
        assertEquals(1, consumer.consume(1, profile).failed());
        assertEquals(0, count("consumer_deliveries"));
        assertEquals(0, count("vacancy_evaluations"));
    }

    private Queue published() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/synthetic/source-item.json")) {
            assertNotNull(stream);
            ingestion.ingest(json.readValue(stream, SyntheticInputDTO.class));
        }
        Queue queue = new Queue();
        assertEquals(1, new SqsProducerService(outbox, queue, json).publish(1).processed());
        assertEquals(1, jdbc.queryForObject("SELECT schema_version FROM outbox_events", Integer.class));
        return queue;
    }

    private SqsEvaluationConsumerService consumer(Queue queue) {
        return new SqsEvaluationConsumerService(queue, outbox, new ConsumerDeliveryRepository(jdbc),
                evaluations, json, clock, manager);
    }

    private LoadedProfileDTO profile(String version) {
        return profiles.loadJson(json.writeValueAsString(new SearchProfileDTO("synthetic", version, "DE",
                List.of("Backend", "Java"), List.of(), List.of(), List.of("ONSITE"), List.of(), false, null)));
    }

    private com.alexs.jobagent.outbox.dto.SqsEvaluationBatchDTO after(CountDownLatch start,
            SqsEvaluationConsumerService consumer, LoadedProfileDTO profile) {
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        return consumer.consume(1, profile);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private UUID eventId() {
        return jdbc.queryForObject("SELECT event_id FROM outbox_events", UUID.class);
    }

    private static class Queue implements SqsGateway {
        volatile String body;
        volatile String sent;
        volatile boolean failDelete;
        volatile CountDownLatch receiveBarrier;
        @Override public void send(String value) { body = value; sent = value; }
        @Override public List<SqsMessageDTO> receive() {
            String delivered = body;
            if (delivered == null) { return List.of(); }
            if (receiveBarrier != null) {
                receiveBarrier.countDown();
                try {
                    assertTrue(receiveBarrier.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            return List.of(new SqsMessageDTO(delivered, UUID.randomUUID().toString()));
        }
        @Override public void delete(String handle) {
            if (failDelete) { throw new IllegalStateException("Synthetic delete failure"); }
            body = null;
        }
    }
}
