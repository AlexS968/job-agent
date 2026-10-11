package com.alexs.jobagent.vacancy.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VacancySourceRepository {
    private final JdbcTemplate jdbc;

    public VacancySourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void link(long vacancyId, long sourceItemId) {
        jdbc.update("INSERT INTO vacancy_sources(vacancy_id, source_item_id) VALUES (?, ?)", vacancyId, sourceItemId);
    }
}
