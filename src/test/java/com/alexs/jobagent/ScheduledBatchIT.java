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
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var first = workers.submit(() -> { start.await(); return repository.startInvocation(date); });
                var second = workers.submit(() -> { start.await(); return repository.startInvocation(date); });
                start.countDown();
                assertTrue(first.get(15, TimeUnit.SECONDS) ^ second.get(15, TimeUnit.SECONDS));
            }
            assertFalse(repository.startInvocation(date.plusDays(5)));
            repository.finishInvocation(date, "FAILED");
            assertTrue(repository.startInvocation(date));
            repository.finishInvocation(date, "COMPLETE");
            assertFalse(repository.startInvocation(date));
            assertTrue(repository.startInvocation(date.plusDays(5)));
        }
    }
}
