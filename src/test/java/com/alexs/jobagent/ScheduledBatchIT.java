package com.alexs.jobagent;

import com.alexs.jobagent.batch.repository.ScheduledBatchRepository;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledBatchIT {
    @Test
    void v16ReportIsRetainedWhenProfileScopedSlotsAreAdded() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("job_agent_scheduled_upgrade").withUsername("test_user")
                .withPassword("synthetic_test_secret_123")) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
            Flyway.configure().dataSource(dataSource).target("16").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            var date = LocalDate.of(2026, 11, 3);
            jdbc.update("INSERT INTO scheduled_batch_reports(schedule_date, status, profile_id, profile_version, "
                    + "report_text, sns_message_id) VALUES (?, 'PUBLISHED', 'historical', 'v1', ?, ?)",
                    date, "historical report", "historical-message");
            Flyway.configure().dataSource(dataSource).load().migrate();
            assertEquals("historical report", jdbc.queryForObject("SELECT report_text FROM scheduled_batch_reports "
                    + "WHERE schedule_date = ? AND profile_id = 'historical'", String.class, date));
            assertEquals("COMPLETE", jdbc.queryForObject("SELECT status FROM scheduled_batch_invocations "
                    + "WHERE schedule_date = ?", String.class, date));
        }
    }

    @Test
    void oneActiveInvocationAndOneCalendarSlotSurviveConcurrentReplay() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("job_agent_scheduled_test").withUsername("test_user")
                .withPassword("synthetic_test_secret_123")) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
            Flyway.configure().dataSource(dataSource).load().migrate();
            var repository = new ScheduledBatchRepository(new JdbcTemplate(dataSource));
            var date = LocalDate.of(2026, 11, 5);
            assertFalse(repository.exists(date, "demo"));
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var first = workers.submit(() -> { start.await(); return repository.startInvocation(date); });
                var second = workers.submit(() -> { start.await(); return repository.startInvocation(date); });
                start.countDown();
                assertTrue(first.get(15, TimeUnit.SECONDS) ^ second.get(15, TimeUnit.SECONDS));
            }
            assertFalse(repository.startInvocation(date.plusDays(5)));
            assertTrue(repository.start(date, "demo-de", "v1", "a".repeat(64)));
            assertTrue(repository.start(date, "demo-at", "v1", "b".repeat(64)));
            assertTrue(repository.exists(date, "demo-de"));
            repository.transition(date, "demo-de", "RUNNING", "PREPARED", "safe report", null);
            repository.transition(date, "demo-de", "PREPARED", "PUBLISHING", null, null);
            repository.transition(date, "demo-de", "PUBLISHING", "PUBLISHED", null, "de-message");
            repository.transition(date, "demo-at", "RUNNING", "PREPARED", "other report", null);
            repository.transition(date, "demo-at", "PREPARED", "PUBLISHING", null, null);
            repository.transition(date, "demo-at", "PUBLISHING", "UNCERTAIN", null, null);
            assertThrows(IllegalStateException.class,
                    () -> repository.transition(date, "demo-at", "PUBLISHING", "PUBLISHED", null, "late-message"));
            repository.finishInvocation(date, "FAILED");
            assertFalse(repository.startInvocation(date));
            assertTrue(repository.startInvocation(date.plusDays(5)));
            assertEquals("UNCERTAIN", new JdbcTemplate(dataSource).queryForObject(
                    "SELECT status FROM scheduled_batch_reports WHERE schedule_date = ? AND profile_id = ?",
                    String.class, date, "demo-at"));
        }
    }

    @Test
    void failedSecondProfileCanResumeWithoutChangingPublishedFirstReport() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("job_agent_scheduled_resume").withUsername("test_user")
                .withPassword("synthetic_test_secret_123")) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
            Flyway.configure().dataSource(dataSource).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            var repository = new ScheduledBatchRepository(jdbc);
            var date = LocalDate.of(2026, 11, 5);
            assertTrue(repository.startInvocation(date));
            assertTrue(repository.start(date, "alex-de", "v1", "a".repeat(64)));
            repository.transition(date, "alex-de", "RUNNING", "PREPARED", "DE report", null);
            repository.transition(date, "alex-de", "PREPARED", "PUBLISHING", null, null);
            repository.transition(date, "alex-de", "PUBLISHING", "PUBLISHED", null, "de-message");
            assertTrue(repository.start(date, "alex-at", "v1", "b".repeat(64)));
            repository.transition(date, "alex-at", "RUNNING", "FAILED", null, null);
            repository.finishInvocation(date, "FAILED");
            assertTrue(repository.startInvocation(date));
            assertFalse(repository.start(date, "alex-de", "v1", "a".repeat(64)));
            assertEquals("PUBLISHED", repository.profileStatus(date, "alex-de", "v1", "a".repeat(64)));
            assertThrows(IllegalStateException.class,
                    () -> repository.profileStatus(date, "alex-de", "v2", "a".repeat(64)));
            assertTrue(repository.retryFailedProfile(date, "alex-at", "v1", "b".repeat(64)));
            repository.transition(date, "alex-at", "RUNNING", "PREPARED", "AT report", null);
            repository.transition(date, "alex-at", "PREPARED", "PUBLISHING", null, null);
            repository.transition(date, "alex-at", "PUBLISHING", "PUBLISHED", null, "at-message");
            repository.finishInvocation(date, "COMPLETE");
            assertFalse(repository.startInvocation(date));
            assertEquals("de-message", jdbc.queryForObject("SELECT sns_message_id FROM scheduled_batch_reports "
                    + "WHERE schedule_date = ? AND profile_id = 'alex-de'", String.class, date));
            assertEquals("at-message", jdbc.queryForObject("SELECT sns_message_id FROM scheduled_batch_reports "
                    + "WHERE schedule_date = ? AND profile_id = 'alex-at'", String.class, date));
        }
    }
}
