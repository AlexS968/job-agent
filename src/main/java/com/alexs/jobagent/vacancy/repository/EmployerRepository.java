package com.alexs.jobagent.vacancy.repository;

import com.alexs.jobagent.vacancy.exception.VacancyConflictException;
import com.alexs.jobagent.vacancy.entity.EmployerEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployerRepository {
    private final JdbcTemplate jdbc;

    public EmployerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long findOrCreate(EmployerEntity employer) {
        Long inserted = jdbc.query("INSERT INTO employers(source_namespace, external_id, name) VALUES (?, ?, ?) "
                        + "ON CONFLICT (source_namespace, external_id) DO NOTHING RETURNING id",
                ps -> {
                    ps.setString(1, employer.sourceNamespace());
                    ps.setString(2, employer.externalId());
                    ps.setString(3, employer.name());
                }, rs -> rs.next() ? rs.getLong(1) : null);
        if (inserted != null) {
            return inserted;
        }
        EmployerEntity existing = jdbc.queryForObject(
                "SELECT id, source_namespace, external_id, name FROM employers WHERE source_namespace = ? AND external_id = ?",
                (rs, row) -> new EmployerEntity(rs.getLong("id"), rs.getString("source_namespace"),
                        rs.getString("external_id"), rs.getString("name")),
                employer.sourceNamespace(), employer.externalId());
        if (!existing.name().equals(employer.name())) {
            throw new VacancyConflictException("Employer identity has conflicting normalized data");
        }
        return existing.id();
    }
}
