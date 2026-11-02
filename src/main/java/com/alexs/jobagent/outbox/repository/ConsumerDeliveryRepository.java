package com.alexs.jobagent.outbox.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import com.alexs.jobagent.outbox.dto.SqsStatusDTO;
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

    public SqsStatusDTO sqsStatus(UUID eventId) {
        return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM consumer_deliveries "
                        + "WHERE consumer_id = 'sqs-vacancy-diagnostic-v1' AND event_id = ?), "
                        + "EXISTS (SELECT 1 FROM sqs_vacancy_observations WHERE event_id = ?)",
                (rs, row) -> new SqsStatusDTO(rs.getBoolean(1), rs.getBoolean(2)), eventId, eventId);
    }
}
