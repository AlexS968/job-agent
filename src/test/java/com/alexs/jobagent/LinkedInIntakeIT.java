package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alexs.jobagent.source.service.GmailReadClient;
import com.alexs.jobagent.source.service.LinkedInIntakeService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class LinkedInIntakeIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("linkedin_test").withUsername("test_user").withPassword("synthetic_test_secret_123");
    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class MailboxConfig {
        @Bean @Primary SyntheticMailbox mailbox() { return new SyntheticMailbox(); }
    }

    static class SyntheticMailbox implements GmailReadClient {
        private List<String> ids = List.of();
        private Map<String, JsonNode> messages = Map.of();
        private String failingId;
        @Override public List<String> alertIds(int limit, LocalDate after, LocalDate before) {
            return ids.stream().limit(limit).toList();
        }
        @Override public JsonNode alert(String id) {
            if (id.equals(failingId)) {
                throw new IllegalStateException("Synthetic temporary Gmail failure");
            }
            return messages.get(id);
        }
    }

    @Autowired LinkedInIntakeService intake;
    @Autowired SyntheticMailbox mailbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE gmail_alert_cards, gmail_alert_messages, vacancy_sources, source_items, "
                + "vacancies, employers, agent_runs RESTART IDENTITY CASCADE");
        mailbox.ids = List.of();
        mailbox.messages = Map.of();
        mailbox.failingId = null;
    }

    @Test
    void distinctMessagesShareVacancyAndReplayHasNoAdditionalEffect() {
        mailbox.ids = List.of("msg1", "msg2");
        mailbox.messages = Map.of("msg1", message("msg1", 1_795_000_000_000L,
                        "<div><a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>"
                        + "<span class='job-location'>Berlin, Germany</span></div>"
                        + "<a href='https://www.linkedin.com/jobs/view/654321/'>Backend Engineer</a>"),
                "msg2", message("msg2", 1_795_000_001_000L,
                        "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>"));
        var first = intake.run(10);
        assertEquals(3, first.cards());
        assertEquals(2, first.created());
        assertEquals(2, count("vacancies"));
        assertEquals(3, count("source_items"));
        assertEquals(3, count("gmail_alert_cards"));
        assertEquals("GERMANY", jdbc.queryForObject("SELECT advertised_direction FROM gmail_alert_cards "
                + "WHERE message_id = 'msg1' AND linkedin_job_id = '123456'", String.class));
        assertEquals(2, count("outbox_events"));
        assertEquals(2, count("gmail_alert_messages"));
        assertEquals(2, intake.run(10).replayed());
        assertEquals(2, count("vacancies"));
        assertEquals(2, count("outbox_events"));
        assertEquals(0, count("gmail_alert_messages WHERE status <> 'COMPLETE'"));
    }

    @Test
    void failureAfterFirstCardRollsBackAllMessageWritesAndCanReplay() {
        mailbox.ids = List.of("msg1");
        mailbox.messages = Map.of("msg1", message("msg1", 1_795_000_000_000L,
                "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>"
                        + "<a href='https://www.linkedin.com/jobs/view/999999/'>Backend Engineer</a>"));
        jdbc.execute("CREATE FUNCTION fail_linkedin_card() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.linkedin_job_id = '999999' THEN RAISE EXCEPTION 'synthetic card failure'; END IF; "
                + "RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_linkedin_card BEFORE INSERT ON gmail_alert_cards "
                + "FOR EACH ROW EXECUTE FUNCTION fail_linkedin_card()");
        try {
            assertThrows(RuntimeException.class, () -> intake.run(10));
            assertEquals(0, count("gmail_alert_messages"));
            assertEquals(0, count("vacancies"));
            assertEquals(0, count("outbox_events"));
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM agent_runs", String.class));
        } finally {
            jdbc.execute("DROP TRIGGER fail_linkedin_card ON gmail_alert_cards");
            jdbc.execute("DROP FUNCTION fail_linkedin_card()");
        }
        assertEquals(2, intake.run(10).created());
        assertEquals(2, count("vacancies"));
    }

    @Test
    void preInterruptedInvocationDoesNotCommit() {
        mailbox.ids = List.of("msg1");
        mailbox.messages = Map.of("msg1", message("msg1", 1_795_000_000_000L,
                "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> intake.run(10));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, count("gmail_alert_messages"));
    }

    @Test
    void unsupportedCardKeepsMessageInReviewAcrossReplay() {
        mailbox.ids = List.of("msg1");
        mailbox.messages = Map.of("msg1", message("msg1", 1_795_000_000_000L,
                "<a href='https://www.linkedin.com/jobs/search/'>Browse jobs</a>"));
        assertEquals(1, intake.run(10).reviewRequired());
        assertEquals("NEEDS_REVIEW", jdbc.queryForObject(
                "SELECT status FROM gmail_alert_messages WHERE message_id = 'msg1'", String.class));
        assertEquals(0, count("vacancies"));
        assertEquals(1, intake.run(10).reviewRequired());
        assertEquals(1, count("gmail_alert_messages"));
    }

    @Test
    void malformedAlertIsRecordedAndNextMessageStillImports() {
        mailbox.ids = List.of("bad", "good");
        mailbox.messages = Map.of("bad", json.readTree("{\"id\":\"bad\",\"internalDate\":\"invalid\"}"),
                "good", message("good", 1_795_000_001_000L,
                        "<a href='https://www.linkedin.com/jobs/view/654321/'>Backend Engineer</a>"));
        var result = intake.run(10);
        assertEquals(1, result.reviewRequired());
        assertEquals(1, result.created());
        assertEquals("INVALID_DATE", jdbc.queryForObject(
                "SELECT review_reason FROM gmail_alert_messages WHERE message_id = 'bad'", String.class));
        assertEquals(1, count("vacancies"));
        assertEquals(2, intake.run(10).replayed());
        assertEquals(1, count("vacancies"));
    }

    @Test
    void temporaryMailboxFailureIsNotRecordedAsPermanentReview() {
        mailbox.ids = List.of("temporary", "good");
        mailbox.failingId = "temporary";
        mailbox.messages = Map.of("good", message("good", 1_795_000_001_000L,
                "<a href='https://www.linkedin.com/jobs/view/654321/'>Backend Engineer</a>"));
        assertThrows(IllegalStateException.class, () -> intake.run(10));
        assertEquals(0, count("gmail_alert_messages"));
        mailbox.failingId = null;
        mailbox.messages = Map.of("temporary", message("temporary", 1_795_000_000_000L,
                "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>"),
                "good", mailbox.messages.get("good"));
        assertEquals(2, intake.run(10).created());
    }

    @Test
    void linkOnlyRepeatKeepsTitleAndLaterRealTitleEnrichesIt() {
        mailbox.ids = List.of("named");
        mailbox.messages = Map.of("named", message("named", 1_795_000_000_000L,
                "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>"));
        assertEquals(1, intake.run(10).created());
        mailbox.ids = List.of("link");
        mailbox.messages = Map.of("link", message("link", 1_795_000_001_000L,
                "<a href='https://www.linkedin.com/jobs/view/123456/'>View job</a>"));
        var repeat = intake.run(10);
        assertEquals(0, repeat.updated());
        assertEquals(1, count("outbox_events"));
        assertEquals("Java Engineer", jdbc.queryForObject(
                "SELECT title FROM vacancies WHERE listing_id = '123456'", String.class));
        assertEquals(2, count("gmail_alert_cards"));

        mailbox.ids = List.of("placeholder");
        mailbox.messages = Map.of("placeholder", message("placeholder", 1_795_000_002_000L,
                "<a href='https://www.linkedin.com/jobs/view/999999/'>View job</a>"));
        assertEquals(1, intake.run(10).created());
        mailbox.ids = List.of("enriched");
        mailbox.messages = Map.of("enriched", message("enriched", 1_795_000_003_000L,
                "<a href='https://www.linkedin.com/jobs/view/999999/'>Senior Java Engineer</a>"));
        assertEquals(1, intake.run(10).updated());
        assertEquals("Senior Java Engineer", jdbc.queryForObject(
                "SELECT title FROM vacancies WHERE listing_id = '999999'", String.class));
    }

    @Test
    void invalidDateWindowStopsBeforeMailboxRequest() {
        assertThrows(IllegalArgumentException.class, () -> intake.run(10,
                LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 9)));
        assertEquals(0, count("gmail_alert_messages"));
    }

    @Test
    void cardBudgetStopsBeforeTheNextMessageWithoutUndoingCommittedCards() {
        StringBuilder first = new StringBuilder();
        for (int id = 1000; id < 1100; id++) {
            first.append("<a href='https://www.linkedin.com/jobs/view/").append(id)
                    .append("/'>Java Engineer</a>");
        }
        mailbox.ids = List.of("first", "later");
        mailbox.messages = Map.of("first", message("first", 1_795_000_000_000L, first.toString()),
                "later", message("later", 1_795_000_001_000L,
                        "<a href='https://www.linkedin.com/jobs/view/999999/'>Backend Engineer</a>"));
        assertEquals("LinkedIn intake card limit reached; earlier messages remain durable",
                assertThrows(IllegalStateException.class, () -> intake.run(2)).getMessage());
        assertEquals(100, count("gmail_alert_cards"));
        assertEquals(1, count("gmail_alert_messages"));
        assertEquals(100, count("vacancies"));
        assertEquals(0, count("gmail_alert_messages WHERE message_id = 'later'"));
    }

    @Test
    void mismatchedGmailIdentityDoesNotRecordAnotherMessagesCards() {
        mailbox.ids = List.of("listed");
        mailbox.messages = Map.of("listed", message("different", 1_795_000_000_000L,
                "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>"));
        assertThrows(IllegalStateException.class, () -> intake.run(1));
        assertEquals(0, count("gmail_alert_messages"));
        assertEquals(0, count("vacancies"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private JsonNode message(String id, long timestamp, String body) {
        String data = Base64.getUrlEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return json.readTree(json.writeValueAsString(Map.of(
                "id", id, "internalDate", Long.toString(timestamp),
                "payload", Map.of("headers", List.of(Map.of("name", "From",
                                "value", "LinkedIn <jobalerts-noreply@linkedin.com>")),
                        "mimeType", "text/html", "body", Map.of("data", data)))));
    }
}
