package com.alexs.jobagent.source.repository;

import com.alexs.jobagent.source.dto.AtsSourceIdentityDTO;
import com.alexs.jobagent.source.dto.SourceVerificationDTO;
import com.alexs.jobagent.source.entity.VerifiedSourceEntity;
import com.alexs.jobagent.source.entity.VerificationDecisionEntity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SourceRegistryRepository {
    private static final String EVIDENCE_HASH_COLUMN = "evidence_hash";
    private static final RowMapper<VerifiedSourceEntity> SOURCE = (rs, row) -> map(rs);
    private final JdbcTemplate jdbc;

    public SourceRegistryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void saveAttempt(SourceVerificationDTO result) {
        AtsSourceIdentityDTO identity = result.identity();
        jdbc.update("""
                INSERT INTO source_verification_attempts
                (candidate_url, origin, claimed_employer_domain, official_page_url, observed_url,
                 observed_at, method_version, outcome, reason, platform, region, source_id, evidence_hash, requests)
                VALUES (?, ?, ?, ?, ?, ?, 'source-verifier-v1', ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (candidate_url, evidence_hash) DO NOTHING
                """, result.inputUrl(), result.origin(), result.officialDomain(), result.officialPageUrl(),
                result.observedUrl(), Timestamp.from(result.observedAt()), result.outcome(), result.reason(),
                identity == null ? null : identity.platform(), identity == null ? null : identity.region(),
                identity == null ? null : identity.sourceId(), result.evidenceHash(), result.requests());
    }

    public VerifiedSourceEntity findIdentityForUpdate(AtsSourceIdentityDTO identity) {
        return jdbc.query("""
                SELECT * FROM verified_source_registry
                WHERE platform = ? AND region = ? AND source_id = ? FOR UPDATE
                """, SOURCE, identity.platform(), identity.region(), identity.sourceId()).stream().findFirst().orElse(null);
    }

    public void insertVerified(SourceVerificationDTO result) {
        AtsSourceIdentityDTO identity = result.identity();
        jdbc.update("""
                INSERT INTO verified_source_registry
                (platform, region, source_id, employer_domain, employer_name, official_page_url,
                 evidence_url, evidence_hash, verified_at, status, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'VERIFIED', FALSE)
                ON CONFLICT (platform, region, source_id) DO NOTHING
                """, identity.platform(), identity.region(), identity.sourceId(), result.officialDomain(),
                result.employerName(), result.officialPageUrl(), result.observedUrl(),
                result.evidenceHash(), Timestamp.from(result.observedAt()));
    }

    public boolean insertDecisionIfAbsent(SourceVerificationDTO result, String decision) {
        AtsSourceIdentityDTO association = "VERIFIED".equals(decision) ? result.identity() : null;
        return jdbc.update("""
                INSERT INTO source_verification_decisions
                (employer_domain, observed_at, decision, evidence_hash,
                 associated_platform, associated_region, associated_source_id)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (employer_domain) DO NOTHING
                """, result.officialDomain(), Timestamp.from(result.observedAt()), decision,
                result.evidenceHash(), association == null ? null : association.platform(),
                association == null ? null : association.region(),
                association == null ? null : association.sourceId()) == 1;
    }

    public VerificationDecisionEntity findDecisionForUpdate(String domain) {
        return jdbc.query("""
                SELECT employer_domain, observed_at, decision, evidence_hash,
                       associated_platform, associated_region, associated_source_id
                FROM source_verification_decisions WHERE employer_domain = ? FOR UPDATE
                """, (rs, row) -> new VerificationDecisionEntity(rs.getString("employer_domain"),
                rs.getTimestamp("observed_at").toInstant(), rs.getString("decision"),
                rs.getString(EVIDENCE_HASH_COLUMN), rs.getString("associated_platform"),
                rs.getString("associated_region"), rs.getString("associated_source_id")),
                domain).stream().findFirst().orElseThrow();
    }

    public void updateDecision(SourceVerificationDTO result, String decision) {
        AtsSourceIdentityDTO association = "VERIFIED".equals(decision) ? result.identity() : null;
        jdbc.update("""
                UPDATE source_verification_decisions
                SET observed_at = ?, decision = ?, evidence_hash = ?,
                    associated_platform = COALESCE(?, associated_platform),
                    associated_region = COALESCE(?, associated_region),
                    associated_source_id = COALESCE(?, associated_source_id)
                WHERE employer_domain = ?
                """, Timestamp.from(result.observedAt()), decision, result.evidenceHash(),
                association == null ? null : association.platform(),
                association == null ? null : association.region(),
                association == null ? null : association.sourceId(), result.officialDomain());
    }

    public void refreshVerified(long id, SourceVerificationDTO result, boolean active) {
        jdbc.update("""
                UPDATE verified_source_registry SET employer_name = ?, official_page_url = ?, evidence_url = ?,
                evidence_hash = ?, verified_at = ?, status = 'VERIFIED', active = ? WHERE id = ?
                """, result.employerName(), result.officialPageUrl(), result.observedUrl(), result.evidenceHash(),
                Timestamp.from(result.observedAt()), active, id);
    }

    public void markReview(long id) {
        jdbc.update("UPDATE verified_source_registry SET status = 'MANUAL_REVIEW', active = FALSE WHERE id = ?", id);
    }

    public void deactivateDomain(String domain, java.time.Instant observedAt, boolean conflict) {
        jdbc.update("""
                UPDATE verified_source_registry
                SET status = CASE WHEN ? OR status = 'MANUAL_REVIEW' THEN 'MANUAL_REVIEW' ELSE 'UNVERIFIED' END,
                    active = FALSE
                WHERE employer_domain = ? AND verified_at <= ?
                """, conflict, domain, Timestamp.from(observedAt));
    }

    public VerifiedSourceEntity findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM verified_source_registry WHERE id = ? FOR UPDATE", SOURCE, id)
                .stream().findFirst().orElse(null);
    }

    public VerifiedSourceEntity findById(long id) {
        return jdbc.query("SELECT * FROM verified_source_registry WHERE id = ?", SOURCE, id)
                .stream().findFirst().orElse(null);
    }

    public List<VerifiedSourceEntity> listSources() {
        return jdbc.query("SELECT * FROM verified_source_registry ORDER BY id LIMIT 100", SOURCE);
    }

    public List<VerifiedSourceEntity> listActive() {
        return jdbc.query("SELECT * FROM verified_source_registry WHERE active = TRUE ORDER BY id LIMIT 6", SOURCE);
    }

    public void setActive(long id, boolean active) {
        jdbc.update("UPDATE verified_source_registry SET active = ? WHERE id = ?", active, id);
    }

    public void markStale(long id) {
        jdbc.update("UPDATE verified_source_registry SET status = 'STALE', active = FALSE WHERE id = ?", id);
    }

    public List<SourceVerificationDTO> listAttempts() {
        return jdbc.query("SELECT * FROM source_verification_attempts ORDER BY id DESC LIMIT 50", (rs, row) -> {
            String platform = rs.getString("platform");
            AtsSourceIdentityDTO identity = platform == null ? null : new AtsSourceIdentityDTO(platform,
                    rs.getString("region"), rs.getString("source_id"));
            return new SourceVerificationDTO(rs.getString("candidate_url"), rs.getString("origin"),
                    rs.getString("claimed_employer_domain"), rs.getString("official_page_url"),
                    rs.getString("observed_url"), rs.getTimestamp("observed_at").toInstant(),
                    rs.getString("outcome"), rs.getString("reason"), identity, null,
                    rs.getInt("requests"), rs.getString(EVIDENCE_HASH_COLUMN));
        });
    }

    private static VerifiedSourceEntity map(ResultSet rs) throws SQLException {
        return new VerifiedSourceEntity(rs.getLong("id"), rs.getString("platform"), rs.getString("region"),
                rs.getString("source_id"), rs.getString("employer_domain"), rs.getString("employer_name"),
                rs.getString("official_page_url"), rs.getString("evidence_url"), rs.getString(EVIDENCE_HASH_COLUMN),
                rs.getTimestamp("verified_at").toInstant(), rs.getString("status"), rs.getBoolean("active"));
    }
}
