package com.alexs.jobagent.source.repository;

import com.alexs.jobagent.source.dto.LinkedInAlertDTO;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GmailAlertRepository {
    private final JdbcTemplate jdbc;

    public GmailAlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                        + "outcome, advertised_direction, direction_evidence) VALUES (?, ?, ?, ?, ?, ?, ?)",
                messageId, card.jobId(), sourceItemId, vacancyId, outcome,
                card.advertisedDirection(), card.directionEvidence());
    }
}
