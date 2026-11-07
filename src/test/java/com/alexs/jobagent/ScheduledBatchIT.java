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
                var first = workers.submit(() -> { start.await(); return repository.start(date, "demo", "v1"); });
                var second = workers.submit(() -> { start.await(); return repository.start(date, "demo", "v1"); });
                start.countDown();
                assertTrue(first.get(15, TimeUnit.SECONDS) ^ second.get(15, TimeUnit.SECONDS));
            }
            assertFalse(repository.start(date.plusDays(5), "demo", "v1"));
            repository.transition(date, "RUNNING", "PREPARED", "safe report", null);
            repository.transition(date, "PREPARED", "PUBLISHING", null, null);
            repository.transition(date, "PUBLISHING", "UNCERTAIN", null, null);
            assertFalse(repository.start(date, "demo", "v1"));
            assertTrue(repository.start(date.plusDays(5), "demo", "v1"));
            assertEquals("UNCERTAIN", new JdbcTemplate(dataSource).queryForObject(
                    "SELECT status FROM scheduled_batch_reports WHERE schedule_date = ?", String.class, date));
        }
    }
}
