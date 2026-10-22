package com.alexs.jobagent.vacancy.repository;

import com.alexs.jobagent.vacancy.exception.VacancyConflictException;
import com.alexs.jobagent.vacancy.entity.VacancyEntity;
import com.alexs.jobagent.vacancy.entity.LiveVacancyEntity;
import com.alexs.jobagent.vacancy.entity.ObservedVacancyEntity;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VacancyRepository {
    private final JdbcTemplate jdbc;

    public Result findOrCreate(VacancyEntity vacancy) {
        Long inserted = jdbc.query("INSERT INTO vacancies(employer_id, source_namespace, listing_id, title, location) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (source_namespace, listing_id) DO NOTHING RETURNING id",
                ps -> {
                    ps.setLong(1, vacancy.employerDbId());
                    ps.setString(2, vacancy.sourceNamespace());
                    ps.setString(3, vacancy.listingId());
                    ps.setString(4, vacancy.title());
                    ps.setString(5, vacancy.location());
                }, rs -> rs.next() ? rs.getLong(1) : null);
        if (inserted != null) {
            return new Result(inserted, true);
        }
        VacancyEntity existing = jdbc.queryForObject(
                "SELECT id, employer_id, source_namespace, listing_id, title, location FROM vacancies "
                        + "WHERE source_namespace = ? AND listing_id = ?",
                (rs, row) -> new VacancyEntity(rs.getLong("id"), rs.getLong("employer_id"),
                        rs.getString("source_namespace"), rs.getString("listing_id"),
                        rs.getString("title"), rs.getString("location")),
                vacancy.sourceNamespace(), vacancy.listingId());
        if (existing.employerDbId() != vacancy.employerDbId()
                || !existing.title().equals(vacancy.title()) || !existing.location().equals(vacancy.location())) {
            throw new VacancyConflictException("Listing identity has conflicting normalized data");
        }
        return new Result(existing.id(), false);
    }

    public VacancyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Result(long id, boolean created) {
    }

    public Long insertLiveIfAbsent(VacancyEntity vacancy, String description, Instant updatedAt, String digest) {
        Long inserted = jdbc.query("INSERT INTO vacancies(employer_id, source_namespace, listing_id, title, location, "
                        + "description, source_updated_at, content_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (source_namespace, listing_id) DO NOTHING RETURNING id",
                ps -> {
                    ps.setLong(1, vacancy.employerDbId());
                    ps.setString(2, vacancy.sourceNamespace());
                    ps.setString(3, vacancy.listingId());
                    ps.setString(4, vacancy.title());
                    ps.setString(5, vacancy.location());
                    ps.setString(6, description);
                    ps.setTimestamp(7, updatedAt == null ? null : Timestamp.from(updatedAt));
                    ps.setString(8, digest);
                }, rs -> rs.next() ? rs.getLong(1) : null);
        return inserted;
    }

    public LiveVacancyEntity lockLive(String namespace, String listingId) {
        return jdbc.queryForObject("SELECT id, employer_id, source_updated_at, content_digest "
                        + "FROM vacancies WHERE source_namespace = ? AND listing_id = ? FOR UPDATE",
                (rs, row) -> new LiveVacancyEntity(rs.getLong(1), rs.getLong(2),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getString(4)),
                namespace, listingId);
    }

    public void updateLive(long id, VacancyEntity vacancy, String description, Instant updatedAt, String digest) {
        jdbc.update("UPDATE vacancies SET title = ?, location = ?, description = ?, source_updated_at = ?, "
                        + "content_digest = ? WHERE id = ?", vacancy.title(), vacancy.location(), description,
                updatedAt == null ? null : Timestamp.from(updatedAt), digest, id);
    }

    public Long insertObservedIfAbsent(VacancyEntity vacancy, String description, Instant observedAt, String digest) {
        return jdbc.query("INSERT INTO vacancies(employer_id, source_namespace, listing_id, title, location, "
                        + "description, observation_started_at, content_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (source_namespace, listing_id) DO NOTHING RETURNING id",
                ps -> {
                    ps.setLong(1, vacancy.employerDbId());
                    ps.setString(2, vacancy.sourceNamespace());
                    ps.setString(3, vacancy.listingId());
                    ps.setString(4, vacancy.title());
                    ps.setString(5, vacancy.location());
                    ps.setString(6, description);
                    ps.setTimestamp(7, Timestamp.from(observedAt));
                    ps.setString(8, digest);
                }, rs -> rs.next() ? rs.getLong(1) : null);
    }

    public ObservedVacancyEntity lockObserved(String namespace, String listingId) {
        return jdbc.queryForObject("SELECT id, employer_id, observation_started_at, content_digest FROM vacancies "
                        + "WHERE source_namespace = ? AND listing_id = ? FOR UPDATE",
                (rs, row) -> new ObservedVacancyEntity(rs.getLong(1), rs.getLong(2),
                        rs.getTimestamp(3).toInstant(), rs.getString(4)), namespace, listingId);
    }

    public void updateObserved(long id, VacancyEntity vacancy, String description, Instant observedAt, String digest) {
        jdbc.update("UPDATE vacancies SET title = ?, location = ?, description = ?, observation_started_at = ?, "
                        + "content_digest = ? WHERE id = ?", vacancy.title(), vacancy.location(), description,
                Timestamp.from(observedAt), digest, id);
    }
}
