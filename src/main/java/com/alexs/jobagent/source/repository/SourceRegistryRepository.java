package com.alexs.jobagent.source.repository;

import com.alexs.jobagent.source.dto.AtsSourceIdentityDTO;
import com.alexs.jobagent.source.dto.SourceVerificationDTO;
import com.alexs.jobagent.source.dto.SourceDiscoveryCandidateDTO;
import com.alexs.jobagent.source.dto.DiscoveryClaimDTO;
import com.alexs.jobagent.source.entity.VerifiedSourceEntity;
import com.alexs.jobagent.source.entity.VerificationDecisionEntity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
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
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (candidate_url, evidence_hash) DO NOTHING
                """, result.inputUrl(), result.origin(), result.officialDomain(), result.officialPageUrl(),
                result.observedUrl(), Timestamp.from(result.observedAt()),
                "automated-official-page".equals(result.origin()) ? "source-verifier-auto-v2" : "source-verifier-v1",
                result.outcome(), result.reason(),
                identity == null ? null : identity.platform(), identity == null ? null : identity.region(),
                identity == null ? null : identity.sourceId(), result.evidenceHash(), result.requests());
    }

    public VerifiedSourceEntity findIdentityForUpdate(AtsSourceIdentityDTO identity) {
        return jdbc.query("""
                SELECT * FROM verified_source_registry
                WHERE platform = ? AND region = ? AND source_id = ? FOR UPDATE
                """, SOURCE, identity.platform(), identity.region(), identity.sourceId()).stream().findFirst().orElse(null);
    }

    public boolean insertVerified(SourceVerificationDTO result) {
        AtsSourceIdentityDTO identity = result.identity();
        return jdbc.update("""
                INSERT INTO verified_source_registry
                (platform, region, source_id, employer_domain, employer_name, official_page_url,
                 evidence_url, evidence_hash, verified_at, status, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'VERIFIED', FALSE)
                ON CONFLICT (platform, region, source_id) DO NOTHING
                """, identity.platform(), identity.region(), identity.sourceId(), result.officialDomain(),
                result.employerName(), result.officialPageUrl(), result.observedUrl(),
                result.evidenceHash(), Timestamp.from(result.observedAt())) == 1;
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
                UPDATE verified_source_registry SET employer_name =
                    CASE WHEN ?='automated-official-page' THEN employer_name ELSE ? END, official_page_url = ?, evidence_url = ?,
                evidence_hash = ?, verified_at = ?, status = 'VERIFIED', active = ?,
                    renew_after = CASE WHEN renew_after IS NOT NULL THEN ?::timestamptz END WHERE id = ?
                """, result.origin(), result.employerName(), result.officialPageUrl(), result.observedUrl(), result.evidenceHash(),
                Timestamp.from(result.observedAt()), active, Timestamp.from(result.observedAt().plus(Duration.ofDays(21))), id);
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

    /** Called inside the short cursor transaction; no network work is done while it is locked. */
    public List<SourceClaim> reserveActiveBatch(int limit) {
        long cursor = jdbc.queryForObject(
                "SELECT last_source_id FROM active_source_import_cursor WHERE singleton = TRUE FOR UPDATE",
                Long.class);
        List<VerifiedSourceEntity> selected = new java.util.ArrayList<>(jdbc.query(
                """
                SELECT s.* FROM verified_source_registry s
                WHERE s.active = TRUE AND s.id > ?
                  AND NOT EXISTS (SELECT 1 FROM active_source_import_claims c
                                  WHERE c.source_id = s.id AND c.claimed_until > CURRENT_TIMESTAMP)
                ORDER BY s.id LIMIT ?
                """,
                SOURCE, cursor, limit));
        if (selected.size() < limit) {
            selected.addAll(jdbc.query(
                    """
                    SELECT s.* FROM verified_source_registry s
                    WHERE s.active = TRUE AND s.id <= ?
                      AND NOT EXISTS (SELECT 1 FROM active_source_import_claims c
                                      WHERE c.source_id = s.id AND c.claimed_until > CURRENT_TIMESTAMP)
                    ORDER BY s.id LIMIT ?
                    """,
                    SOURCE, cursor, limit - selected.size()));
        }
        List<SourceClaim> claims = new java.util.ArrayList<>();
        for (VerifiedSourceEntity source : selected) {
            java.util.UUID token = java.util.UUID.randomUUID();
            int recorded = jdbc.update("""
                    INSERT INTO active_source_import_claims (source_id, claim_token, claimed_until)
                    VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '30 minutes')
                    ON CONFLICT (source_id) DO UPDATE
                    SET claim_token = EXCLUDED.claim_token, claimed_until = EXCLUDED.claimed_until
                    WHERE active_source_import_claims.claimed_until <= CURRENT_TIMESTAMP
                    """, source.id(), token);
            if (recorded != 1) {
                throw new IllegalStateException("Source import claim could not be acquired");
            }
            claims.add(new SourceClaim(source, token));
        }
        if (!selected.isEmpty()) {
            jdbc.update("UPDATE active_source_import_cursor SET last_source_id = ? WHERE singleton = TRUE",
                    selected.get(selected.size() - 1).id());
        }
        return List.copyOf(claims);
    }

    public void releaseImportClaim(long sourceId, java.util.UUID token) {
        jdbc.update("DELETE FROM active_source_import_claims WHERE source_id = ? AND claim_token = ?", sourceId, token);
    }

    public record SourceClaim(VerifiedSourceEntity source, java.util.UUID token) { }

    public record RenewalClaim(VerifiedSourceEntity source, Instant retryAfter) { }

    public void activateAutomatically(long id, Instant observed) {
        jdbc.update("UPDATE verified_source_registry SET active=TRUE, renew_after=? WHERE id=?",
                Timestamp.from(observed.plus(Duration.ofDays(21))), id);
    }

    /** The retry time also fences this attempt if a worker stops during the HTTP request. */
    public RenewalClaim claimRenewal(Instant now, Instant oldestValid) {
        Instant retryAfter = now.plus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM verified_source_registry
                    WHERE active AND status='VERIFIED' AND renew_after <= ? AND verified_at >= ?
                    ORDER BY renew_after,id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE verified_source_registry s SET renew_after=? FROM due WHERE s.id=due.id RETURNING s.*
                """, rs -> rs.next() ? new RenewalClaim(map(rs), retryAfter) : null,
                Timestamp.from(now), Timestamp.from(oldestValid), Timestamp.from(retryAfter));
    }

    public boolean lockCurrentRenewal(RenewalClaim claim) {
        return !jdbc.queryForList("""
                SELECT id FROM verified_source_registry WHERE id=? AND active AND status='VERIFIED'
                  AND verified_at=? AND renew_after=? FOR UPDATE
                """, Long.class, claim.source().id(), Timestamp.from(claim.source().verifiedAt()),
                Timestamp.from(claim.retryAfter())).isEmpty();
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

    public List<SourceDiscoveryCandidateDTO> listDiscoveryCandidates(long afterId) {
        return jdbc.query("""
                SELECT id, candidate_url, first_seen_at, last_seen_at, verification_outcome,
                       verification_reason, official_domain, platform, region, source_id
                FROM source_discovery_candidates WHERE id > ? ORDER BY id LIMIT 50
                """, (rs, row) -> {
            String platform = rs.getString("platform");
            return new SourceDiscoveryCandidateDTO(rs.getLong("id"), rs.getString("candidate_url"),
                    rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(),
                    rs.getString("verification_outcome"), rs.getString("verification_reason"),
                    rs.getString("official_domain"), platform == null ? null
                    : new AtsSourceIdentityDTO(platform, rs.getString("region"), rs.getString("source_id")));
        }, afterId);
    }

    public String discoveryCandidateUrl(long id) {
        return jdbc.query("SELECT candidate_url FROM source_discovery_candidates WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, id);
    }

    public boolean activeIdentity(AtsSourceIdentityDTO identity) {
        if (identity == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.query("""
                SELECT active FROM verified_source_registry
                WHERE platform = ? AND region = ? AND source_id = ? AND status = 'VERIFIED'
                """, rs -> rs.next() && rs.getBoolean(1), identity.platform(), identity.region(),
                identity.sourceId()));
    }

    /** Called inside the short claim transaction; the caller performs HTTP only after commit. */
    public List<DiscoveryClaimDTO> claimDiscoveryBatch(java.time.Instant now, int limit) {
        jdbc.update("""
                UPDATE source_discovery_candidates
                SET verification_outcome = 'MANUAL_REVIEW',
                    verification_reason = CASE WHEN lead_kind = 'AGGREGATOR'
                                               THEN 'AGGREGATOR_ONLY_LEAD'
                                               WHEN lead_kind = 'NON_EMPLOYER'
                                               THEN 'NON_EMPLOYER_SITE' ELSE 'UNSAFE_URL' END,
                    claim_token = NULL, claimed_until = NULL
                WHERE lead_kind IN ('AGGREGATOR', 'NON_EMPLOYER', 'UNSAFE')
                  AND verification_outcome IN ('UNVERIFIED', 'RETRYABLE_FAILURE')
                  AND (claimed_until IS NULL OR claimed_until <= ?)
                """, Timestamp.from(now));
        jdbc.update("""
                UPDATE source_discovery_candidates
                SET verification_outcome = 'MANUAL_REVIEW', verification_reason = 'RETRY_EXHAUSTED',
                    claim_token = NULL, claimed_until = NULL
                WHERE verification_attempts >= 3 AND claimed_until <= ?
                  AND verification_outcome IN ('UNVERIFIED', 'RETRYABLE_FAILURE')
                """, Timestamp.from(now));
        List<DiscoveryClaimDTO> pending = jdbc.query("""
                SELECT id, candidate_url FROM source_discovery_candidates
                WHERE verification_outcome IN ('UNVERIFIED', 'RETRYABLE_FAILURE')
                  AND lead_kind IN ('ATS', 'EMPLOYER')
                  AND verification_attempts < 3 AND next_attempt_at <= ?
                  AND (claimed_until IS NULL OR claimed_until <= ?)
                ORDER BY lead_priority, id LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new DiscoveryClaimDTO(rs.getLong(1), rs.getString(2), null),
                Timestamp.from(now), Timestamp.from(now), limit);
        return pending.stream().map(item -> {
            java.util.UUID token = java.util.UUID.randomUUID();
            jdbc.update("""
                    UPDATE source_discovery_candidates SET claim_token = ?, claimed_until = ?,
                        verification_attempts = verification_attempts + 1 WHERE id = ?
                    """, token, Timestamp.from(now.plus(java.time.Duration.ofMinutes(5))), item.id());
            return new DiscoveryClaimDTO(item.id(), item.candidateUrl(), token);
        }).toList();
    }

    public boolean claimValidForUpdate(DiscoveryClaimDTO claim, java.time.Instant now) {
        return !jdbc.query("""
                SELECT id FROM source_discovery_candidates
                WHERE id = ? AND claim_token = ? AND claimed_until > ? FOR UPDATE
                """, (rs, row) -> rs.getLong(1), claim.id(), claim.token(), Timestamp.from(now)).isEmpty();
    }

    public String finishDiscoveryClaim(DiscoveryClaimDTO claim, SourceVerificationDTO result,
                                       java.time.Instant now) {
        AtsSourceIdentityDTO identity = result.identity();
        List<String> outcomes = jdbc.query("""
                UPDATE source_discovery_candidates SET verification_outcome =
                    CASE WHEN ? = 'RETRYABLE_FAILURE' AND verification_attempts >= 3
                         THEN 'MANUAL_REVIEW' ELSE ? END,
                    verification_reason =
                    CASE WHEN ? = 'RETRYABLE_FAILURE' AND verification_attempts >= 3
                         THEN 'RETRY_EXHAUSTED' ELSE ? END,
                    official_domain = ?, official_page_url = ?, platform = ?, region = ?, source_id = ?,
                    next_attempt_at = ?, claim_token = NULL, claimed_until = NULL
                WHERE id = ? AND claim_token = ? RETURNING verification_outcome
                """, (rs, row) -> rs.getString(1), result.outcome(), result.outcome(),
                result.outcome(), result.reason(),
                result.officialDomain(), result.officialPageUrl(),
                identity == null ? null : identity.platform(), identity == null ? null : identity.region(),
                identity == null ? null : identity.sourceId(),
                Timestamp.from(now.plus(java.time.Duration.ofMinutes(30))), claim.id(), claim.token());
        if (outcomes.size() != 1) {
            throw new IllegalStateException("Discovery claim was superseded");
        }
        return outcomes.getFirst();
    }

    public void recordDiscoveryVerification(SourceVerificationDTO result) {
        AtsSourceIdentityDTO identity = result.identity();
        jdbc.update("""
                UPDATE source_discovery_candidates SET verification_outcome = ?, verification_reason = ?,
                    official_domain = ?, official_page_url = ?, platform = ?, region = ?, source_id = ?
                WHERE candidate_url = ?
                """, result.outcome(), result.reason(), result.officialDomain(), result.officialPageUrl(),
                identity == null ? null : identity.platform(), identity == null ? null : identity.region(),
                identity == null ? null : identity.sourceId(), result.inputUrl());
    }

    private static VerifiedSourceEntity map(ResultSet rs) throws SQLException {
        return new VerifiedSourceEntity(rs.getLong("id"), rs.getString("platform"), rs.getString("region"),
                rs.getString("source_id"), rs.getString("employer_domain"), rs.getString("employer_name"),
                rs.getString("official_page_url"), rs.getString("evidence_url"), rs.getString(EVIDENCE_HASH_COLUMN),
                rs.getTimestamp("verified_at").toInstant(), rs.getString("status"), rs.getBoolean("active"));
    }
}
