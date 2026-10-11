package com.alexs.jobagent.vacancy.repository;

import com.alexs.jobagent.vacancy.exception.VacancyConflictException;
import com.alexs.jobagent.vacancy.entity.VacancyEntity;
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
}
