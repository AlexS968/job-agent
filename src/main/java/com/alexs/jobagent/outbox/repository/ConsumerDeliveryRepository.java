package com.alexs.jobagent.outbox.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import com.alexs.jobagent.outbox.dto.SqsStatusDTO;
import com.alexs.jobagent.outbox.dto.SqsEvaluationEffectDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConsumerDeliveryRepository {
    private final JdbcTemplate jdbc;

    public ConsumerDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean recordDeliveryIfAbsent(String consumerId, UUID eventId, Instant now) {
        return jdbc.update("INSERT INTO consumer_deliveries(consumer_id, event_id, processed_at) "
                        + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING", consumerId, eventId, Timestamp.from(now)) == 1;
    }

    public void observeVacancy(long vacancyId, UUID eventId, Instant now) {
        jdbc.update("INSERT INTO synthetic_vacancy_observations(vacancy_id, first_event_id, observed_at) "
                        + "VALUES (?, ?, ?)", vacancyId, eventId, Timestamp.from(now));
    }

    public void observeSqs(UUID eventId, long vacancyId, Instant now) {
        jdbc.update("INSERT INTO sqs_vacancy_observations(event_id, vacancy_id, observed_at) VALUES (?, ?, ?)",
                eventId, vacancyId, Timestamp.from(now));
    }

    public void recordEvaluationEffect(String consumerId, UUID eventId, String outcome, Long evaluationId,
                                       Instant now) {
        jdbc.update("INSERT INTO sqs_evaluation_effects(consumer_id, event_id, outcome, evaluation_id, recorded_at) "
                        + "VALUES (?, ?, ?, ?, ?)", consumerId, eventId, outcome, evaluationId, Timestamp.from(now));
    }

    public SqsEvaluationEffectDTO evaluationEffect(String consumerId, UUID eventId) {
        return jdbc.query("SELECT outcome, evaluation_id FROM sqs_evaluation_effects "
                        + "WHERE consumer_id = ? AND event_id = ?",
                rs -> rs.next() ? new SqsEvaluationEffectDTO(rs.getString(1), rs.getObject(2, Long.class)) : null,
                consumerId, eventId);
    }

    public SqsStatusDTO sqsStatus(UUID eventId) {
        return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM consumer_deliveries "
                        + "WHERE consumer_id = 'sqs-vacancy-diagnostic-v1' AND event_id = ?), "
                        + "EXISTS (SELECT 1 FROM sqs_vacancy_observations WHERE event_id = ?)",
                (rs, row) -> new SqsStatusDTO(rs.getBoolean(1), rs.getBoolean(2)), eventId, eventId);
    }
}
