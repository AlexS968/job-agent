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

    public boolean start(LocalDate date, String profileId, String profileVersion) {
        return jdbc.update("""
                INSERT INTO scheduled_batch_reports (schedule_date, status, profile_id, profile_version)
                VALUES (?, 'RUNNING', ?, ?) ON CONFLICT DO NOTHING
                """, date, profileId, profileVersion) == 1;
    }

    public boolean exists(LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM scheduled_batch_reports WHERE schedule_date = ?)",
                Boolean.class, date));
    }

    public void transition(LocalDate date, String from, String to, String report, String messageId) {
        int updated = jdbc.update("""
                UPDATE scheduled_batch_reports
                SET status = ?, report_text = COALESCE(?, report_text),
                    sns_message_id = COALESCE(?, sns_message_id),
                    finished_at = CASE WHEN ? IN ('PUBLISHED', 'FAILED', 'UNCERTAIN')
                        THEN CURRENT_TIMESTAMP ELSE finished_at END
                WHERE schedule_date = ? AND status = ?
                """, to, report, messageId, to, date, from);
        if (updated != 1) {
            throw new IllegalStateException("Scheduled batch state changed unexpectedly");
        }
    }
}
