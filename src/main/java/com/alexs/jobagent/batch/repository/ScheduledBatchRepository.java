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

    public boolean start(LocalDate date, String profileId, String profileVersion, String criteriaHash) {
        return jdbc.update("""
                INSERT INTO scheduled_batch_reports (schedule_date, status, profile_id, profile_version, criteria_hash)
                VALUES (?, 'RUNNING', ?, ?, ?) ON CONFLICT DO NOTHING
                """, date, profileId, profileVersion, criteriaHash) == 1;
    }

    public boolean startInvocation(LocalDate date) {
        if (jdbc.update("INSERT INTO scheduled_batch_invocations (schedule_date, status) "
                + "VALUES (?, 'RUNNING') ON CONFLICT DO NOTHING", date) == 1) {
            return true;
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM scheduled_batch_reports "
                + "WHERE schedule_date = ? AND status IN ('RUNNING', 'PREPARED', 'PUBLISHING', 'UNCERTAIN'))",
                Boolean.class, date))) {
            return false;
        }
        return jdbc.update("UPDATE scheduled_batch_invocations SET status = 'RUNNING', finished_at = NULL "
                + "WHERE schedule_date = ? AND status = 'FAILED'", date) == 1;
    }

    public String invocationStatus(LocalDate date) {
        return jdbc.query("SELECT status FROM scheduled_batch_invocations WHERE schedule_date = ?",
                rs -> rs.next() ? rs.getString(1) : null, date);
    }

    public String profileStatus(LocalDate date, String profileId, String version, String criteriaHash) {
        return jdbc.query("SELECT status, profile_version, criteria_hash FROM scheduled_batch_reports "
                        + "WHERE schedule_date = ? AND profile_id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    if (!version.equals(rs.getString(2)) || !criteriaHash.equals(rs.getString(3))) {
                        throw new IllegalStateException("Scheduled profile identity differs from its durable slot");
                    }
                    return rs.getString(1);
                }, date, profileId);
    }

    public boolean retryFailedProfile(LocalDate date, String profileId, String version, String criteriaHash) {
        return jdbc.update("UPDATE scheduled_batch_reports SET status = 'RUNNING', report_text = NULL, "
                + "sns_message_id = NULL, finished_at = NULL WHERE schedule_date = ? AND profile_id = ? "
                + "AND profile_version = ? AND criteria_hash = ? AND status = 'FAILED'",
                date, profileId, version, criteriaHash) == 1;
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

    public boolean exists(LocalDate date, String profileId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM scheduled_batch_reports WHERE schedule_date = ? AND profile_id = ?)",
                Boolean.class, date, profileId));
    }

    public void transition(LocalDate date, String profileId, String from, String to, String report, String messageId) {
        int updated = jdbc.update("""
                UPDATE scheduled_batch_reports
                SET status = ?, report_text = COALESCE(?, report_text),
                    sns_message_id = COALESCE(?, sns_message_id),
                    finished_at = CASE WHEN ? IN ('PUBLISHED', 'FAILED', 'UNCERTAIN')
                        THEN CURRENT_TIMESTAMP ELSE finished_at END
                WHERE schedule_date = ? AND profile_id = ? AND status = ?
                """, to, report, messageId, to, date, profileId, from);
        if (updated != 1) {
            throw new IllegalStateException("Scheduled batch state changed unexpectedly");
        }
    }
}
