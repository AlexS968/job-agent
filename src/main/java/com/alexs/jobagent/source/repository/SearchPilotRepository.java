package com.alexs.jobagent.source.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SearchPilotRepository {
    private final JdbcTemplate jdbc;

    public SearchPilotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean incrementIfBelowLimit() {
        return jdbc.update("UPDATE search_pilot_budget SET reserved_attempts = reserved_attempts + 1 "
                + "WHERE id = 1 AND reserved_attempts < 100") == 1;
    }

    public long insertAttempt(String queryVersion, int queryNumber) {
        return jdbc.queryForObject("INSERT INTO search_pilot_attempts (query_version, query_number) "
                + "VALUES (?, ?) RETURNING id", Long.class, queryVersion, queryNumber);
    }

    public boolean reserveMonthly(java.time.LocalDate period) {
        return !jdbc.query("""
                INSERT INTO search_monthly_budget (period_start, reserved_attempts) VALUES (?, 1)
                ON CONFLICT (period_start) DO UPDATE
                    SET reserved_attempts = search_monthly_budget.reserved_attempts + 1
                    WHERE search_monthly_budget.reserved_attempts < 120
                RETURNING reserved_attempts
                """, (rs, row) -> rs.getInt(1), period).isEmpty();
    }

    public long insertMonthlyAttempt(String queryVersion, int queryNumber, java.time.LocalDate period) {
        return jdbc.queryForObject("""
                INSERT INTO search_pilot_attempts (query_version, query_number, budget_period)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, queryVersion, queryNumber, period);
    }

    public int monthlyRemaining(java.time.LocalDate period) {
        Integer reserved = jdbc.query("SELECT reserved_attempts FROM search_monthly_budget WHERE period_start = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, period);
        return 120 - reserved;
    }

    public void finish(long attemptId, String outcome, String requestId, Integer credits) {
        jdbc.update("UPDATE search_pilot_attempts SET outcome = ?, provider_request_id = ?, provider_credits = ? "
                + "WHERE id = ?", outcome, safeId(requestId), credits, attemptId);
    }

    public void recordCandidate(long attemptId, String url) {
        jdbc.update("""
                INSERT INTO source_discovery_candidates
                (candidate_url, first_search_attempt_id, last_search_attempt_id)
                VALUES (?, ?, ?)
                ON CONFLICT (candidate_url) DO UPDATE SET
                    last_search_attempt_id = EXCLUDED.last_search_attempt_id,
                    last_seen_at = CURRENT_TIMESTAMP
                """, url, attemptId, attemptId);
    }

    public int remaining() {
        return jdbc.queryForObject("SELECT 100 - reserved_attempts FROM search_pilot_budget WHERE id = 1", Integer.class);
    }

    private static String safeId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,128}") ? id : null;
    }
}
