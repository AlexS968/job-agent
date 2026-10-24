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

    public void finish(long attemptId, String outcome, String requestId, Integer credits) {
        jdbc.update("UPDATE search_pilot_attempts SET outcome = ?, provider_request_id = ?, provider_credits = ? "
                + "WHERE id = ?", outcome, safeId(requestId), credits, attemptId);
    }

    public int remaining() {
        return jdbc.queryForObject("SELECT 100 - reserved_attempts FROM search_pilot_budget WHERE id = 1", Integer.class);
    }

    private static String safeId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,128}") ? id : null;
    }
}
