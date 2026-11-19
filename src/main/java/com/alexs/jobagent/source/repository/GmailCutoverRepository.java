package com.alexs.jobagent.source.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GmailCutoverRepository {
    private final JdbcTemplate jdbc;

    public GmailCutoverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Window(long start, Long end, String token, long generation) {}
    public record Claim(String id, int attempt) {}

    public Window window(long overlapSeconds) {
        jdbc.update("""
                UPDATE gmail_cutover SET window_end=floor(extract(epoch FROM now()))::bigint,
                  scan_start=greatest(since_epoch, window_start-?)
                WHERE enabled AND window_end IS NULL AND window_start < floor(extract(epoch FROM now()))
                """, overlapSeconds);
        return jdbc.query("""
                SELECT coalesce(scan_start,window_start),window_end,page_token,generation
                FROM gmail_cutover WHERE enabled
                """, rs -> rs.next() ? new Window(rs.getLong(1), rs.getObject(2, Long.class),
                rs.getString(3), rs.getLong(4)) : null);
    }

    public void advance(Window window, String next) {
        int changed = jdbc.update("""
                UPDATE gmail_cutover SET generation=generation+1, page_token=?,
                  window_start=CASE WHEN ?::text IS NULL THEN window_end ELSE window_start END,
                  window_end=CASE WHEN ?::text IS NULL THEN NULL ELSE window_end END,
                  scan_start=CASE WHEN ?::text IS NULL THEN NULL ELSE scan_start END
                WHERE enabled AND generation=? AND window_end=?
                """, next, next, next, next, window.generation(), window.end());
        if (changed != 1) throw new IllegalStateException("Gmail cursor changed during intake");
    }

    public void restart(Window window) {
        int changed = jdbc.update("""
                UPDATE gmail_cutover SET page_token=NULL,generation=generation+1
                WHERE enabled AND generation=? AND window_end=? AND page_token=?
                """, window.generation(), window.end(), window.token());
        if (changed != 1) throw new IllegalStateException("Gmail cursor changed during token recovery");
    }

    public Claim claim() {
        return jdbc.query("""
                UPDATE gmail_mailbox_actions SET attempts=attempts+1,next_attempt=now()+interval '30 minutes'
                WHERE message_id=(SELECT message_id FROM gmail_mailbox_actions
                  WHERE applied_at IS NULL AND next_attempt<=now() AND attempts<5
                    AND EXISTS (SELECT FROM gmail_cutover WHERE enabled)
                  ORDER BY next_attempt,message_id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING message_id,attempts
                """, rs -> rs.next() ? new Claim(rs.getString(1), rs.getInt(2)) : null);
    }

    public void applied(Claim claim) {
        if (jdbc.update("""
                UPDATE gmail_mailbox_actions SET applied_at=now()
                WHERE message_id=? AND attempts=? AND applied_at IS NULL
                """, claim.id(), claim.attempt()) != 1) throw new IllegalStateException("Mailbox claim changed");
    }

    public long outstanding() {
        return jdbc.queryForObject("SELECT count(*) FROM gmail_mailbox_actions WHERE applied_at IS NULL", Long.class);
    }
}
