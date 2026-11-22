package com.alexs.jobagent.batch.repository;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ScheduledBatchRepository {
    private final JdbcTemplate jdbc;

    public ScheduledBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean startInvocation(LocalDate date) {
        if (jdbc.update("INSERT INTO scheduled_batch_invocations (schedule_date, status) "
                + "VALUES (?, 'RUNNING') ON CONFLICT DO NOTHING", date) == 1) {
            return true;
        }
        return jdbc.update("UPDATE scheduled_batch_invocations SET status = 'RUNNING', finished_at = NULL "
                + "WHERE schedule_date = ? AND status = 'FAILED'", date) == 1;
    }

    public String invocationStatus(LocalDate date) {
        return jdbc.query("SELECT status FROM scheduled_batch_invocations WHERE schedule_date = ?",
                rs -> rs.next() ? rs.getString(1) : null, date);
    }

    public void finishInvocation(LocalDate date, String status) {
        if (!"COMPLETE".equals(status) && !"FAILED".equals(status)) {
            throw new IllegalArgumentException("Invalid scheduled invocation status");
        }
        if (jdbc.update("UPDATE scheduled_batch_invocations SET status = ?, finished_at = CURRENT_TIMESTAMP "
                + "WHERE schedule_date = ? AND status = 'RUNNING'", status, date) != 1) {
            throw new IllegalStateException("Scheduled invocation state changed unexpectedly");
        }
    }
}
