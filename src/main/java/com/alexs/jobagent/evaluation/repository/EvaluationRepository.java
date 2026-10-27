package com.alexs.jobagent.evaluation.repository;

import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class EvaluationRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public EvaluationRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void registerProfile(LoadedProfileDTO loaded) {
        jdbc.update("INSERT INTO evaluation_profiles(profile_id, profile_version, criteria_hash) "
                        + "VALUES (?, ?, ?) ON CONFLICT (profile_id, profile_version) DO NOTHING",
                loaded.profile().profileId(), loaded.profile().version(), loaded.criteriaHash());
        requireProfile(loaded);
    }

    public void requireProfile(LoadedProfileDTO loaded) {
        String stored = jdbc.query("SELECT criteria_hash FROM evaluation_profiles WHERE profile_id = ? "
                        + "AND profile_version = ?", rs -> rs.next() ? rs.getString(1) : null,
                loaded.profile().profileId(), loaded.profile().version());
        if (stored != null && !stored.equals(loaded.criteriaHash())) {
            throw new IllegalArgumentException("Profile version has different criteria; use a new version");
        }
    }

    public void requireRegisteredProfile(LoadedProfileDTO loaded) {
        requireProfile(loaded);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM evaluation_profiles WHERE profile_id = ? "
                        + "AND profile_version = ? AND criteria_hash = ?", Integer.class,
                loaded.profile().profileId(), loaded.profile().version(), loaded.criteriaHash());
        if (count == null || count == 0) {
            throw new IllegalArgumentException("Profile version has not been evaluated");
        }
    }

    public boolean insert(VacancyEvaluationDTO result, LoadedProfileDTO profile, String policyVersion) {
        return jdbc.update("INSERT INTO vacancy_evaluations(vacancy_id, source_item_id, vacancy_revision, profile_id, "
                        + "profile_version, criteria_hash, policy_version, decision, preference_rank, "
                        + "role_classification, reasons, unknowns, evidence, original_url) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, "
                        + "?::jsonb, ?) ON CONFLICT ON CONSTRAINT vacancy_evaluation_identity DO NOTHING",
                result.vacancyId(), result.sourceItemId(), result.vacancyRevision(), profile.profile().profileId(),
                profile.profile().version(), profile.criteriaHash(), policyVersion, result.decision(),
                result.preferenceRank(), result.roleClassification(), json.writeValueAsString(result.reasons()),
                json.writeValueAsString(result.unknowns()), json.writeValueAsString(result.evidence()),
                result.originalUrl()) == 1;
    }

    public VacancyEvaluationDTO current(long vacancyId, String revision, LoadedProfileDTO profile,
                                        String policyVersion) {
        return jdbc.query("SELECT vacancy_id, source_item_id, vacancy_revision, decision, preference_rank, "
                        + "role_classification, reasons::text, "
                        + "unknowns::text, evidence::text, original_url FROM vacancy_evaluations "
                        + "WHERE vacancy_id = ? AND vacancy_revision = ? AND profile_id = ? "
                        + "AND profile_version = ? AND criteria_hash = ? AND policy_version = ?",
                rs -> rs.next() ? new VacancyEvaluationDTO(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6), strings(rs.getString(7)),
                        strings(rs.getString(8)), strings(rs.getString(9)), rs.getString(10)) : null,
                vacancyId, revision, profile.profile().profileId(), profile.profile().version(),
                profile.criteriaHash(), policyVersion);
    }

    private List<String> strings(String value) {
        return Arrays.asList(json.readValue(value, String[].class));
    }
}
