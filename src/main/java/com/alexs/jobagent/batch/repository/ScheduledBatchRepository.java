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

    public boolean hasUnresolvedResult() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM scheduled_result_reports "
                + "WHERE status IN ('PREPARED', 'PUBLISHING', 'UNCERTAIN'))", Boolean.class));
    }

    public boolean hasUnresolvedLegacyReport() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM scheduled_batch_reports "
                + "WHERE status IN ('RUNNING', 'PREPARED', 'PUBLISHING', 'UNCERTAIN'))", Boolean.class));
    }

    public boolean hasLegacyReportOn(LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM scheduled_batch_reports "
                + "WHERE schedule_date = ?)", Boolean.class, date));
    }

    public boolean hasPublishedLegacyReport(String profileId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM scheduled_batch_reports "
                + "WHERE profile_id = ? AND status = 'PUBLISHED')", Boolean.class, profileId));
    }

    public boolean hasPublishedResult() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM scheduled_result_reports "
                + "WHERE status = 'PUBLISHED')", Boolean.class));
    }

    public String resultStatus(LocalDate date) {
        return jdbc.query("SELECT status FROM scheduled_result_reports WHERE schedule_date = ?",
                rs -> rs.next() ? rs.getString(1) : null, date);
    }

    public boolean wasPublished(String profileId, long vacancyId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM scheduled_result_items i "
                        + "JOIN scheduled_result_reports r ON r.schedule_date = i.schedule_date "
                        + "WHERE r.status = 'PUBLISHED' AND i.profile_id = ? AND i.vacancy_id = ?)",
                Boolean.class, profileId, vacancyId));
    }

    /** A first-time profile match is newly discovered only if its first stored source follows the prior result. */
    public boolean firstObservedAfterLastResult(long vacancyId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT CASE WHEN NOT EXISTS (
                    SELECT 1 FROM scheduled_result_reports WHERE status = 'PUBLISHED'
                ) THEN TRUE ELSE COALESCE(
                    (SELECT MIN(si.created_at) FROM vacancy_sources vs
                     JOIN source_items si ON si.id = vs.source_item_id WHERE vs.vacancy_id = ?)
                    > (SELECT MAX(r.finished_at) FROM scheduled_result_reports r
                       WHERE r.status = 'PUBLISHED'), FALSE) END
                """, Boolean.class, vacancyId));
    }

    public void prepareResult(LocalDate date, String deVersion, String deHash, String atVersion, String atHash,
                              String subject, String body) {
        if (jdbc.update("INSERT INTO scheduled_result_reports(schedule_date, status, de_profile_version, "
                        + "de_criteria_hash, at_profile_version, at_criteria_hash, subject, report_text) "
                        + "VALUES (?, 'PREPARED', ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                date, deVersion, deHash, atVersion, atHash, subject, body) != 1) {
            throw new IllegalStateException("Combined report slot already exists");
        }
    }

    public void recordResultItem(LocalDate date, String profileId, long vacancyId, String revision,
                                 String profileVersion, String criteriaHash, String evaluationPolicy,
                                 String countryPolicy, String disposition) {
        if (jdbc.update("INSERT INTO scheduled_result_items(schedule_date, profile_id, vacancy_id, "
                        + "vacancy_revision, profile_version, criteria_hash, evaluation_policy, "
                        + "country_policy, disposition) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                date, profileId, vacancyId, revision, profileVersion, criteriaHash, evaluationPolicy,
                countryPolicy, disposition) != 1) {
            throw new IllegalStateException("Combined report item was not recorded");
        }
    }

    public void transitionResult(LocalDate date, String from, String to, String messageId) {
        if (jdbc.update("UPDATE scheduled_result_reports SET status = ?, sns_message_id = COALESCE(?, sns_message_id), "
                        + "finished_at = CASE WHEN ? IN ('PUBLISHED', 'UNCERTAIN') "
                        + "THEN CURRENT_TIMESTAMP ELSE finished_at END "
                        + "WHERE schedule_date = ? AND status = ?",
                to, messageId, to, date, from) != 1) {
            throw new IllegalStateException("Combined report state changed unexpectedly");
        }
    }
}
