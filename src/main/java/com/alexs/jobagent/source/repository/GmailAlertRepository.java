package com.alexs.jobagent.source.repository;

import com.alexs.jobagent.source.dto.LinkedInAlertDTO;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GmailAlertRepository {
    private final JdbcTemplate jdbc;

    public GmailAlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueueLiveAction(String id) {
        if (jdbc.update("""
                INSERT INTO gmail_mailbox_actions(message_id)
                SELECT ? FROM gmail_cutover WHERE enabled FOR SHARE
                """, id) != 1) throw new IllegalStateException("Mailbox cutover paused during intake");
    }

    public String status(String id) {
        return jdbc.query("SELECT status FROM gmail_alert_messages WHERE message_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, id);
    }

    public boolean record(LinkedInAlertDTO alert) {
        String status = alert.unsupportedLinks() == 0 && !alert.cards().isEmpty()
                ? "COMPLETE" : "NEEDS_REVIEW";
        return jdbc.update("INSERT INTO gmail_alert_messages(message_id, observed_at, recognized_cards, "
                        + "unsupported_links, status, review_reason, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (message_id) DO NOTHING", alert.messageId(),
                Timestamp.from(alert.observedAt()), alert.cards().size(), alert.unsupportedLinks(), status,
                "COMPLETE".equals(status) ? null : alert.cards().isEmpty() ? "NO_RECOGNIZED_CARDS" : "UNRECOGNIZED_LINK",
                "COMPLETE".equals(status) ? Timestamp.from(java.time.Instant.now()) : null) == 1;
    }

    public boolean recordReview(String messageId, String reason) {
        return jdbc.update("INSERT INTO gmail_alert_messages(message_id, observed_at, recognized_cards, "
                        + "unsupported_links, status, review_reason) VALUES (?, CURRENT_TIMESTAMP, 0, 0, "
                        + "'NEEDS_REVIEW', ?) ON CONFLICT (message_id) DO NOTHING", messageId, reason) == 1;
    }

    public void card(String messageId, LinkedInAlertDTO.CardDTO card, long sourceItemId,
                     long vacancyId, String outcome) {
        jdbc.update("INSERT INTO gmail_alert_cards(message_id, linkedin_job_id, source_item_id, vacancy_id, "
                        + "outcome, advertised_direction, direction_evidence, advertised_title, advertised_employer, "
                        + "advertised_location, parsed_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                messageId, card.jobId(), sourceItemId, vacancyId, outcome,
                card.advertisedDirection(), card.directionEvidence(), card.hasAdvertisedTitle() ? card.title() : null,
                card.advertisedEmployer(), card.directionEvidence(), card.advertisedDirection());
    }

    public boolean repairEligible(String id) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT FROM gmail_alert_messages WHERE message_id = ?
                  AND status = 'NEEDS_REVIEW' AND review_reason = 'UNRECOGNIZED_LINK'
                  AND pre_repair_unsupported_links IS NULL)
                """, Boolean.class, id);
    }

    public Instant lockRepairCandidate(String id) {
        return jdbc.query("""
                SELECT observed_at FROM gmail_alert_messages WHERE message_id = ?
                  AND status = 'NEEDS_REVIEW' AND review_reason = 'UNRECOGNIZED_LINK'
                  AND pre_repair_unsupported_links IS NULL FOR UPDATE
                """, rs -> rs.next() ? rs.getTimestamp(1).toInstant() : null, id);
    }

    public List<String> cardIds(String id) {
        return jdbc.queryForList("SELECT linkedin_job_id FROM gmail_alert_cards WHERE message_id = ?", String.class, id);
    }

    public void storeCardFields(String id, LinkedInAlertDTO.CardDTO card) {
        String title = card.hasAdvertisedTitle() ? card.title() : null;
        jdbc.update("""
                UPDATE gmail_alert_cards SET advertised_title = coalesce(?, advertised_title), advertised_employer = ?,
                    advertised_location = ?, parsed_direction = ?
                WHERE message_id = ? AND linkedin_job_id = ?
                """, title, card.advertisedEmployer(), card.directionEvidence(), card.advertisedDirection(),
                id, card.jobId());
    }

    public void recordRepair(String id, int unsupportedLinks) {
        boolean complete = unsupportedLinks == 0;
        jdbc.update("""
                UPDATE gmail_alert_messages SET pre_repair_unsupported_links = unsupported_links,
                    unsupported_links = ?, status = ?, review_reason = ?, completed_at = ?
                WHERE message_id = ?
                """, unsupportedLinks, complete ? "COMPLETE" : "NEEDS_REVIEW",
                complete ? null : "UNRECOGNIZED_LINK", complete ? Timestamp.from(Instant.now()) : null, id);
    }
}
