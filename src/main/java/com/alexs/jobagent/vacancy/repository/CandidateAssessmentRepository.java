package com.alexs.jobagent.vacancy.repository;

import com.alexs.jobagent.vacancy.dto.CandidateAssessmentDTO;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CandidateAssessmentRepository {
    private final JdbcTemplate jdbc;

    public CandidateAssessmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> pending(String version, int limit) {
        return jdbc.query("""
                SELECT si.id FROM source_items si
                JOIN vacancy_sources vs ON vs.source_item_id = si.id
                JOIN vacancies v ON v.id = vs.vacancy_id
                WHERE si.source_namespace NOT LIKE 'synthetic-%'
                  AND si.content_digest IS NOT DISTINCT FROM v.content_digest
                  AND NOT EXISTS (SELECT 1 FROM vacancy_candidate_assessments a
                                  WHERE a.source_item_id = si.id AND a.policy_version = ?)
                ORDER BY si.id LIMIT ?
                """, (rs, row) -> rs.getLong(1), version, limit);
    }

    public CandidateInput lockCurrent(long sourceItemId) {
        return jdbc.query("""
                SELECT v.id, v.title, v.description FROM vacancies v
                JOIN vacancy_sources vs ON vs.vacancy_id = v.id
                JOIN source_items si ON si.id = vs.source_item_id
                WHERE si.id = ? AND si.content_digest IS NOT DISTINCT FROM v.content_digest
                FOR UPDATE OF v
                """, (rs, row) -> new CandidateInput(rs.getLong(1), rs.getString(2), rs.getString(3)),
                sourceItemId).stream().findFirst().orElse(null);
    }

    public boolean record(long sourceItemId, long vacancyId, String version, CandidateAssessmentDTO assessment) {
        return jdbc.update("""
                INSERT INTO vacancy_candidate_assessments
                (vacancy_id, source_item_id, policy_version, decision, reason)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (source_item_id, policy_version) DO NOTHING
                """, vacancyId, sourceItemId, version, assessment.decision(), assessment.reason()) == 1;
    }

    public CandidateAssessmentDTO find(long sourceItemId, String version) {
        return jdbc.query("""
                SELECT decision, reason FROM vacancy_candidate_assessments
                WHERE source_item_id = ? AND policy_version = ?
                """, rs -> rs.next() ? new CandidateAssessmentDTO(rs.getString(1), rs.getString(2)) : null,
                sourceItemId, version);
    }

    public record CandidateInput(long vacancyId, String title, String description) { }
}
