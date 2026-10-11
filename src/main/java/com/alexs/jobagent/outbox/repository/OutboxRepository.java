package com.alexs.jobagent.outbox.repository;

import com.alexs.jobagent.outbox.entity.OutboxEventEntity;
import com.alexs.jobagent.outbox.dto.BacklogDTO;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(UUID eventId, long vacancyId, long runId, Instant now) {
        jdbc.update("INSERT INTO outbox_events(event_id, event_type, schema_version, aggregate_id, occurred_at, "
                        + "run_id, payload, next_attempt_at) VALUES (?, 'VacancyDiscovered', 1, ?, ?, ?, "
                        + "jsonb_build_object('vacancy_id', ?::bigint), ?)",
                eventId, vacancyId, Timestamp.from(now), runId, vacancyId, Timestamp.from(now));
    }

    public int expireExhaustedClaims(Instant now, int maxAttempts, int limit) {
        return jdbc.update("WITH expired AS (SELECT event_id FROM outbox_events "
                        + "WHERE state = 'CLAIMED' AND lease_until <= ? AND cycle_attempts >= ? "
                        + "ORDER BY lease_until, event_id FOR UPDATE SKIP LOCKED LIMIT ?) "
                        + "UPDATE outbox_events e SET state = 'FAILED', failure_code = 'LEASE_EXHAUSTED', "
                        + "claimed_by = NULL, claim_token = NULL, lease_until = NULL "
                        + "FROM expired WHERE e.event_id = expired.event_id",
                Timestamp.from(now), maxAttempts, limit);
    }

    public OutboxEventEntity claim(String worker, UUID token, Instant now, Instant leaseUntil, int maxAttempts) {
        List<OutboxEventEntity> rows = jdbc.query("WITH candidate AS (SELECT event_id FROM outbox_events "
                        + "WHERE (state = 'PENDING' AND next_attempt_at <= ? AND cycle_attempts < ?) "
                        + "OR (state = 'CLAIMED' AND lease_until <= ? AND cycle_attempts < ?) "
                        + "ORDER BY occurred_at, event_id FOR UPDATE SKIP LOCKED LIMIT 1) "
                        + "UPDATE outbox_events e SET state = 'CLAIMED', claimed_by = ?, claim_token = ?, "
                        + "lease_until = ?, total_attempts = total_attempts + 1, "
                        + "cycle_attempts = cycle_attempts + 1 FROM candidate c WHERE e.event_id = c.event_id "
                        + "RETURNING e.event_id, e.event_type, e.schema_version, e.aggregate_id, e.occurred_at, "
                        + "e.run_id, e.payload::text, e.claim_token, e.cycle_attempts",
                (rs, row) -> new OutboxEventEntity(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                        rs.getLong(4), rs.getTimestamp(5).toInstant(), rs.getLong(6), rs.getString(7),
                        rs.getObject(8, UUID.class), rs.getInt(9)),
                Timestamp.from(now), maxAttempts, Timestamp.from(now), maxAttempts,
                worker, token, Timestamp.from(leaseUntil));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public BacklogDTO backlog(Instant now) {
        return jdbc.queryForObject("SELECT "
                        + "COUNT(*) FILTER (WHERE state = 'PENDING' AND next_attempt_at <= ?), "
                        + "COUNT(*) FILTER (WHERE state = 'PENDING' AND next_attempt_at > ?), "
                        + "COUNT(*) FILTER (WHERE state = 'CLAIMED' AND lease_until > ?), "
                        + "COUNT(*) FILTER (WHERE state = 'CLAIMED' AND lease_until <= ?), "
                        + "COUNT(*) FILTER (WHERE state = 'FAILED') FROM outbox_events",
                (rs, row) -> new BacklogDTO(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        rs.getInt(4), rs.getInt(5)),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    public boolean complete(UUID eventId, UUID token, Instant now) {
        return jdbc.update("UPDATE outbox_events SET state = 'COMPLETED', completed_at = ?, "
                        + "claimed_by = NULL, claim_token = NULL, lease_until = NULL, failure_code = NULL "
                        + "WHERE event_id = ? AND claim_token = ? AND state = 'CLAIMED' AND lease_until > ?",
                Timestamp.from(now), eventId, token, Timestamp.from(now)) == 1;
    }

    public boolean fail(UUID eventId, UUID token, Instant now, Instant nextAttempt, int maxAttempts, String code) {
        return jdbc.update("UPDATE outbox_events SET state = CASE WHEN cycle_attempts >= ? THEN 'FAILED' "
                        + "ELSE 'PENDING' END, next_attempt_at = ?, failure_code = ?, "
                        + "claimed_by = NULL, claim_token = NULL, lease_until = NULL "
                        + "WHERE event_id = ? AND claim_token = ? AND state = 'CLAIMED' AND lease_until > ?",
                maxAttempts, Timestamp.from(nextAttempt), code, eventId, token, Timestamp.from(now)) == 1;
    }

    public boolean replay(UUID eventId, Instant now) {
        return jdbc.update("UPDATE outbox_events SET state = 'PENDING', cycle_attempts = 0, "
                        + "next_attempt_at = ?, failure_code = NULL WHERE event_id = ? AND state = 'FAILED'",
                Timestamp.from(now), eventId) == 1;
    }
}
