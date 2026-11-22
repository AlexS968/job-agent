package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alexs.jobagent.source.service.GmailReadClient;
import com.alexs.jobagent.source.service.GmailCutoverClient;
import com.alexs.jobagent.source.service.GmailCutoverService;
import com.alexs.jobagent.source.exception.InvalidGmailPageTokenException;
import com.alexs.jobagent.source.service.LinkedInIntakeService;
import com.alexs.jobagent.source.service.LinkedInRepairService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;
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

@SpringBootTest(properties = "jobagent.gmail.cutover=true")
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

    static class SyntheticMailbox implements GmailReadClient, GmailCutoverClient {
        java.util.function.Function<String, Page> page;
        java.util.function.Consumer<String> modify;
        long lastStart;
        long lastEnd;
        @Override public Page livePage(long start, long end, String token) {
            lastStart = start;
            lastEnd = end;
            return page.apply(token);
        }
        @Override public String processedLabel() { return "Label_1"; }
        @Override public void applyLabels(String id, String label) { modify.accept(id); }
        private List<String> ids = List.of();
        private Map<String, JsonNode> messages = Map.of();
        private String failingId;
        private java.util.function.Consumer<String> onRead;
        @Override public List<String> alertIds(int limit, LocalDate after, LocalDate before) {
            return ids.stream().limit(limit).toList();
        }
        @Override public JsonNode alert(String id) {
            if (id.equals(failingId)) {
                throw new IllegalStateException("Synthetic temporary Gmail failure");
            }
            onRead.accept(id);
            return messages.get(id);
        }
    }

    @Autowired GmailCutoverService cutover;
    @Autowired LinkedInIntakeService intake;
    @Autowired com.alexs.jobagent.source.repository.GmailCutoverRepository gmailState;
    @Autowired LinkedInRepairService repair;
    @Autowired com.alexs.jobagent.source.service.EmployerMonitoringService monitoring;
    @Autowired SyntheticMailbox mailbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE gmail_cutover, gmail_alert_cards, gmail_alert_messages, vacancy_sources, source_items, "
                + "vacancies, employers, agent_runs RESTART IDENTITY CASCADE");
        mailbox.ids = List.of();
        mailbox.messages = Map.of();
        mailbox.page = token -> { throw new AssertionError("Unexpected live list"); };
        mailbox.modify = id -> { throw new AssertionError("Unexpected mailbox change"); };
        mailbox.failingId = null;
        mailbox.onRead = id -> { };
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

    @Test
    void disabledCutoverNeverListsOrModifiesMail() {
        assertEquals(false, cutover.run().active());
    }

    @Test
    void savedPageTokenAdvancesPastRehearsalAndReviewToEligibleMessage() {
        long now = System.currentTimeMillis() - 60_000;
        String card = "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>";
        mailbox.ids = List.of("rehearsed");
        mailbox.messages = Map.of("rehearsed", message("rehearsed", now, card),
                "review", message("review", now, "No identifiable cards"), "eligible", message("eligible", now, card));
        intake.run(1);
        jdbc.update("INSERT INTO gmail_cutover(enabled,since_epoch,window_start) VALUES(true,?,?)",
                now / 1000, now / 1000);
        mailbox.page = token -> token == null ? new GmailCutoverClient.Page(List.of("rehearsed", "review"), "next")
                : new GmailCutoverClient.Page(List.of("eligible"), null);
        mailbox.modify = id -> {
            assertEquals("eligible", id);
            assertTrue(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, count("gmail_alert_messages WHERE message_id='eligible' AND status='COMPLETE'"));
            assertEquals(1, count("gmail_mailbox_actions WHERE message_id='eligible' AND applied_at IS NULL"));
        };
        var first = cutover.run();
        assertTrue(first.morePages());
        assertEquals(1, first.review());
        assertEquals(0, first.applied());
        var second = cutover.run();
        assertEquals(false, second.morePages());
        assertEquals(1, second.applied());
        assertEquals(0, second.outstanding());
        assertEquals(1, count("gmail_mailbox_actions"));
    }

    @Test
    void weekdayIntakeCapsMondayBacklogAndResumesSavedToken() {
        long now = Instant.now().getEpochSecond();
        long since = now - 3 * 86_400;
        jdbc.update("INSERT INTO gmail_cutover(enabled,since_epoch,window_start) VALUES(true,?,?)", since, since);
        String card = "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>";
        mailbox.messages = Map.of("first", message("first", (since + 1) * 1000, card),
                "last", message("last", (since + 2) * 1000, card));
        var tokens = new java.util.ArrayList<String>();
        mailbox.page = token -> {
            tokens.add(token);
            return switch (token == null ? "" : token) {
                case "" -> new GmailCutoverClient.Page(List.of("first"), "second");
                case "second" -> new GmailCutoverClient.Page(List.of("first"), "third");
                case "third" -> new GmailCutoverClient.Page(List.of("first"), "fourth");
                case "fourth" -> new GmailCutoverClient.Page(List.of("last"), null);
                default -> throw new AssertionError(token);
            };
        };
        var applied = new java.util.ArrayList<String>();
        mailbox.modify = applied::add;

        assertTrue(cutover.runUntil(Instant.now().plusSeconds(180)).morePages());
        assertEquals("fourth", jdbc.queryForObject("SELECT page_token FROM gmail_cutover", String.class));
        assertEquals(1, count("gmail_alert_messages"));
        assertEquals(3, tokens.size());
        assertTrue(cutover.runUntil(Instant.now().plusSeconds(5)).morePages());
        assertEquals(3, tokens.size());
        assertEquals(false, cutover.runUntil(Instant.now().plusSeconds(180)).morePages());
        assertEquals("fourth", tokens.getLast());
        assertEquals(List.of("first", "last"), applied);
        assertEquals(1, count("vacancies"));
        assertEquals(2, count("gmail_alert_messages"));
    }

    @Test
    void partialPageAtDeadlineRetainsCursorAndCommittedMessage() {
        Instant start = Instant.now();
        long since = start.getEpochSecond() - 60;
        jdbc.update("INSERT INTO gmail_cutover(enabled,since_epoch,window_start) VALUES(true,?,?)", since, since);
        String card = "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>";
        mailbox.messages = Map.of("first", message("first", (since + 1) * 1000, card),
                "last", message("last", (since + 2) * 1000, card));
        var current = new java.util.concurrent.atomic.AtomicReference<>(start);
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.instant()).thenAnswer(call -> current.get());
        var bounded = new GmailCutoverService(gmailState, intake, mailbox, 7200, clock);
        mailbox.page = token -> {
            org.junit.jupiter.api.Assertions.assertNull(token);
            return new GmailCutoverClient.Page(List.of("first", "last"), null);
        };
        // Advance the clock during the first message read, after the page has been fetched.
        mailbox.onRead = id -> current.set(start.plusSeconds(140));
        assertTrue(bounded.runUntil(start.plusSeconds(180)).morePages());
        assertEquals(1, count("gmail_alert_messages"));
        assertEquals(1, count("gmail_cutover WHERE window_end IS NOT NULL AND page_token IS NULL"));
        mailbox.modify = id -> { };
        mailbox.onRead = id -> { };
        assertEquals(false, bounded.runUntil(start.plusSeconds(320)).morePages());
        assertEquals(2, count("gmail_alert_messages"));
        assertEquals(1, count("vacancies"));
    }

    @Test
    void lostAcknowledgementRetriesCommittedActionWithoutImportingAgain() {
        long now = System.currentTimeMillis() - 60_000;
        jdbc.update("INSERT INTO gmail_cutover(enabled,since_epoch,window_start) VALUES(true,?,?)",
                now / 1000, now / 1000);
        mailbox.page = token -> new GmailCutoverClient.Page(List.of("eligible"), null);
        mailbox.messages = Map.of("eligible", message("eligible", now,
                "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>"));
        var calls = new java.util.ArrayList<String>();
        mailbox.modify = calls::add;
        jdbc.execute("ALTER TABLE gmail_mailbox_actions ADD CONSTRAINT synthetic_ack_failure CHECK (applied_at IS NULL)");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, cutover::run);
        } finally {
            jdbc.execute("ALTER TABLE gmail_mailbox_actions DROP CONSTRAINT synthetic_ack_failure");
        }
        assertEquals(1, count("gmail_mailbox_actions WHERE applied_at IS NULL AND attempts=1"));
        jdbc.update("UPDATE gmail_mailbox_actions SET next_attempt=now()");
        mailbox.failingId = "eligible";
        cutover.run();
        assertEquals(List.of("eligible", "eligible"), calls);
        assertEquals(1, count("gmail_mailbox_actions WHERE applied_at IS NOT NULL AND attempts=2"));
        assertEquals(1, count("agent_runs"));
        assertEquals(1, count("outbox_events"));
    }

    @Test
    void nextWindowFindsLateIndexedMailAndAdvancesPastOverlap() {
        long end = java.time.Instant.now().getEpochSecond() - 60;
        long since = end - 300;
        jdbc.update("""
                INSERT INTO gmail_cutover(enabled,since_epoch,window_start,window_end,scan_start)
                VALUES(true,?,?,?,?)
                """, since, since, end, since);
        String card = "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>";
        mailbox.messages = Map.of("early", message("early", (end - 30) * 1000, card),
                "late", message("late", (end - 20) * 1000, card));
        mailbox.page = token -> new GmailCutoverClient.Page(List.of("early"), null);
        var modified = new java.util.ArrayList<String>();
        mailbox.modify = modified::add;
        assertEquals(1, cutover.run().applied());
        assertEquals(end, jdbc.queryForObject("SELECT window_start FROM gmail_cutover", Long.class));

        mailbox.page = token -> {
            assertEquals(since, mailbox.lastStart);
            assertTrue(mailbox.lastEnd > end);
            return new GmailCutoverClient.Page(List.of("early", "late"), null);
        };
        assertEquals(1, cutover.run().applied());
        assertEquals(List.of("early", "late"), modified);
        assertEquals(mailbox.lastEnd, jdbc.queryForObject("SELECT window_start FROM gmail_cutover", Long.class));
        assertEquals(2, count("gmail_alert_messages"));
        assertEquals(2, count("gmail_mailbox_actions"));
        assertEquals(1, count("vacancies"));
    }

    @Test
    void explicitlyRejectedTokenRestartsSameWindowOnNextInvocation() {
        long end = java.time.Instant.now().getEpochSecond() - 60;
        long start = end - 300;
        jdbc.update("""
                INSERT INTO gmail_cutover(enabled,since_epoch,window_start,window_end,scan_start,page_token)
                VALUES(true,?,?,?,?,'stale')
                """, start, start + 60, end, start);
        mailbox.page = token -> { throw new IllegalStateException("Synthetic HTTP 503"); };
        assertThrows(IllegalStateException.class, cutover::run);
        assertEquals("stale", jdbc.queryForObject("SELECT page_token FROM gmail_cutover", String.class));

        mailbox.page = token -> {
            assertEquals("stale", token);
            throw new InvalidGmailPageTokenException();
        };
        assertTrue(cutover.run().windowRestartRequired());
        assertEquals(null, jdbc.queryForObject("SELECT page_token FROM gmail_cutover", String.class));
        mailbox.page = token -> {
            assertEquals(null, token);
            assertEquals(start, mailbox.lastStart);
            assertEquals(end, mailbox.lastEnd);
            return new GmailCutoverClient.Page(List.of(), null);
        };
        assertEquals(false, cutover.run().morePages());
        assertEquals(end, jdbc.queryForObject("SELECT window_start FROM gmail_cutover", Long.class));
    }

    @Test
    void livePageSkipsMailBeforeHandoffAndAtWindowEnd() {
        long end = java.time.Instant.now().getEpochSecond() - 60;
        long since = end - 300;
        jdbc.update("""
                INSERT INTO gmail_cutover(enabled,since_epoch,window_start,window_end,scan_start)
                VALUES(true,?,?,?,?)
                """, since, since, end, since);
        String card = "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>";
        mailbox.messages = Map.of("before", message("before", since * 1000 - 1, card),
                "future", message("future", end * 1000, card));
        mailbox.page = token -> new GmailCutoverClient.Page(List.of("before", "future"), null);

        var result = cutover.run();
        assertEquals(2, result.seen());
        assertEquals(0, result.applied());
        assertEquals(0, count("gmail_alert_messages"));
        assertEquals(0, count("vacancies"));
        assertEquals(0, count("gmail_mailbox_actions"));
        assertEquals(end, jdbc.queryForObject("SELECT window_start FROM gmail_cutover", Long.class));
    }

    @Test
    void pausingAfterListingRollsBackCompletionAndLeavesPageForRetry() {
        long since = java.time.Instant.now().getEpochSecond() - 60;
        jdbc.update("INSERT INTO gmail_cutover(enabled,since_epoch,window_start) VALUES(true,?,?)", since, since);
        mailbox.messages = Map.of("eligible", message("eligible", since * 1000,
                "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>"));
        mailbox.page = token -> {
            jdbc.update("UPDATE gmail_cutover SET enabled=false");
            return new GmailCutoverClient.Page(List.of("eligible"), "next");
        };

        assertEquals("Mailbox cutover paused during intake",
                assertThrows(IllegalStateException.class, cutover::run).getMessage());
        assertEquals(0, count("gmail_alert_messages"));
        assertEquals(0, count("vacancies"));
        assertEquals(0, count("outbox_events"));
        assertEquals(0, count("gmail_mailbox_actions"));
        assertEquals(since, jdbc.queryForObject("SELECT window_start FROM gmail_cutover", Long.class));
        assertEquals(1, count("gmail_cutover WHERE page_token IS NULL AND window_end IS NOT NULL"));
    }

    @Test
    void repairsLegacyReviewWithoutReplacingProvenanceOrReplayingEffects() {
        long observed = 1_795_000_000_000L;
        String legacyTitle = "Backend Engineer Example Works Belgium (Hybrid) View job";
        mailbox.ids = List.of("legacy");
        mailbox.messages = Map.of("legacy", message("legacy", observed,
                "<a href='https://www.linkedin.com/jobs/view/123456/'>" + legacyTitle + "</a>"
                        + "<a href='https://www.linkedin.com/jobs/search/'>Legacy unsupported link</a>"));
        assertEquals(1, intake.run(1).reviewRequired());
        jdbc.update("""
                UPDATE gmail_alert_cards SET advertised_title=NULL,advertised_employer=NULL,
                    advertised_location=NULL,parsed_direction=NULL
                """);
        var original = jdbc.queryForMap("SELECT id,source_item_id,fetched_at,raw_payload::text FROM source_items");
        var vacancy = jdbc.queryForMap("SELECT id,title,content_digest,observation_started_at FROM vacancies");
        assertEquals(legacyTitle, vacancy.get("title"));
        mailbox.messages = Map.of("legacy", message("legacy", observed, """
                <a href='https://www.linkedin.com/jobs/view/123456/'>
                  <h3>Backend Engineer</h3><div class='job-company'>Example Works</div>
                  <span class='job-location'>Belgium (Hybrid)</span><button>View job</button>
                </a>
                <a href='https://www.linkedin.com/comm/jobs/search-results/'>Search</a>
                <a href='https://www.linkedin.com/comm/jobs/alerts'>Alerts</a>
                """));

        var result = repair.repair(List.of("legacy"));
        assertEquals(1, result.completed());
        assertEquals(1, result.repairedCards());
        assertEquals(0, result.reviewRequired());
        assertEquals(1, count("""
                gmail_alert_messages WHERE status='COMPLETE' AND review_reason IS NULL
                AND unsupported_links=0 AND pre_repair_unsupported_links=1
                """));
        assertEquals(1, count("""
                gmail_alert_cards WHERE advertised_title='Backend Engineer' AND advertised_employer='Example Works'
                AND advertised_location='Belgium (Hybrid)' AND parsed_direction='UNKNOWN'
                """));

        // The named, repaired card can seed monitoring independently of mailbox writes or a company URL.
        var disabled = new com.alexs.jobagent.source.dto.DiscoveryBatchDTO(false, 0, 0, 0, 0, 0, 0, 0, 0, false);
        monitoring.runUntil(java.time.Instant.MAX, disabled);
        monitoring.runUntil(java.time.Instant.MAX, disabled);
        assertEquals(1, count("vacancy_employer_leads WHERE advertised_name='Example Works'"
                + " AND status='WAITING_FOR_SEARCH' AND attempts=0 AND registry_id IS NULL"));
        mailbox.failingId = "legacy";  // Replay must skip the Gmail fetch.
        assertEquals(1, repair.repair(List.of("legacy")).skipped());
        assertEquals(original, jdbc.queryForMap("SELECT id,source_item_id,fetched_at,raw_payload::text FROM source_items"));
        assertEquals(vacancy, jdbc.queryForMap("SELECT id,title,content_digest,observation_started_at FROM vacancies"));
        assertEquals(1, count("gmail_alert_cards"));
        assertEquals(1, count("vacancy_sources"));
        assertEquals(1, count("outbox_events"));
        assertEquals(0, count("gmail_mailbox_actions"));
    }

    @Test
    void repairKeepsOriginalReviewWhenReparsedCardIdentitiesDiffer() {
        long observed = 1_795_000_000_000L;
        mailbox.ids = List.of("legacy");
        mailbox.messages = Map.of("legacy", message("legacy", observed, """
                <a href='https://www.linkedin.com/jobs/view/123456/'>Engineer</a>
                <a href='https://www.linkedin.com/jobs/search/'>Unrecognized</a>
                """));
        intake.run(1);
        mailbox.messages = Map.of("legacy", message("legacy", observed,
                "<a href='https://www.linkedin.com/jobs/view/654321/'>Different role</a>"));

        assertEquals(1, repair.repair(List.of("legacy")).reviewRequired());
        assertEquals(1, count("""
                gmail_alert_messages WHERE status='NEEDS_REVIEW' AND review_reason='UNRECOGNIZED_LINK'
                AND pre_repair_unsupported_links IS NULL
                """));
        assertEquals(1, count("gmail_alert_cards WHERE linkedin_job_id='123456'"));
        assertEquals(1, count("source_items"));
        assertEquals(1, count("outbox_events"));
        assertEquals(0, count("gmail_mailbox_actions"));
    }

    @Test
    void malformedRepairLeavesOriginalEvidenceAndDoesNotBlockLaterCardCorrection() {
        long observed = 1_795_000_000_000L;
        String unsupportedLink = "<a href='https://www.linkedin.com/jobs/search/'>Unrecognized</a>";
        mailbox.ids = List.of("malformed", "partial");
        mailbox.messages = Map.of(
                "malformed", message("malformed", observed,
                        "<a href='https://www.linkedin.com/jobs/view/123456/'>First engineer</a>" + unsupportedLink),
                "partial", message("partial", observed,
                        "<a href='https://www.linkedin.com/jobs/view/654321/'>Second engineer</a>" + unsupportedLink));
        assertEquals(2, intake.run(2).reviewRequired());
        mailbox.messages = Map.of(
                "malformed", json.readTree("{}"),
                "partial", message("partial", observed, """
                        <a href='https://www.linkedin.com/jobs/view/654321/'>
                          <span class='job-location'>Remote Europe</span>
                        </a>
                        """ + unsupportedLink));

        assertEquals(new LinkedInRepairService.Result(2, 0, 2, 0, 1), repair.repair(mailbox.ids));
        assertEquals(1, count("""
                gmail_alert_messages WHERE message_id='malformed' AND status='NEEDS_REVIEW'
                AND pre_repair_unsupported_links IS NULL
                """));
        assertEquals(1, count("""
                gmail_alert_messages WHERE message_id='partial' AND status='NEEDS_REVIEW'
                AND review_reason='UNRECOGNIZED_LINK' AND unsupported_links=1 AND pre_repair_unsupported_links=1
                """));
        assertEquals(1, count("""
                gmail_alert_cards WHERE message_id='partial' AND advertised_title='Second engineer'
                AND parsed_direction='REMOTE_EUROPE'
                """));
        mailbox.failingId = "partial";
        assertEquals(new LinkedInRepairService.Result(1, 0, 0, 1, 0), repair.repair(List.of("partial")));
        assertEquals(2, count("source_items"));
        assertEquals(2, count("outbox_events"));
        assertEquals(0, count("gmail_mailbox_actions"));
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
