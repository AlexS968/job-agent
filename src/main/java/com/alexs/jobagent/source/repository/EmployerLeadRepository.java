package com.alexs.jobagent.source.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployerLeadRepository {
    private final JdbcTemplate jdbc;

    public EmployerLeadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Observation(long vacancy, long item, String name, String url, String site,
                              String apply, String title, String description) { }
    public record Claim(long id, UUID token, String name, String url, String site, String apply,
                        boolean needsSearch) { }

    public List<Observation> unseen() {
        return jdbc.query("""
                WITH named AS (
                    SELECT v.id AS vacancy, si.id AS item, si.fetched_at, si.original_url, v.description,
                           coalesce(c.advertised_title, v.title) AS title,
                           trim(CASE WHEN si.source_namespace = 'himalayas' THEN si.raw_payload->>'companyName'
                                     ELSE c.advertised_employer END) AS name,
                           si.raw_payload->>'companyWebsite' AS site, si.apply_url AS apply
                    FROM source_items si JOIN vacancy_sources vs ON vs.source_item_id = si.id
                    JOIN vacancies v ON v.id = vs.vacancy_id
                    LEFT JOIN gmail_alert_cards c ON c.source_item_id = si.id
                    WHERE (si.source_namespace = 'himalayas' AND si.content_digest = v.content_digest
                           AND jsonb_typeof(si.raw_payload->'companyName') = 'string')
                       OR (si.source_namespace = 'linkedin:gmail' AND c.advertised_employer IS NOT NULL)
                ), latest AS (
                    SELECT DISTINCT ON (vacancy, name) * FROM named
                    WHERE length(name) BETWEEN 1 AND 255
                    ORDER BY vacancy, name, fetched_at DESC, item DESC
                )
                SELECT n.* FROM latest n LEFT JOIN vacancy_employer_leads l
                    ON l.vacancy_id=n.vacancy AND l.advertised_name=n.name
                WHERE l.id IS NULL OR (l.status='SKIPPED' AND n.item<>l.source_item_id)
                ORDER BY n.vacancy, n.name LIMIT 60
                """, (r, i) -> new Observation(r.getLong("vacancy"), r.getLong("item"), r.getString("name"),
                r.getString("original_url"), r.getString("site"), r.getString("apply"), r.getString("title"),
                r.getString("description")));
    }

    public void saveObservation(Observation row, boolean relevant, String reason) {
        jdbc.update("""
                INSERT INTO vacancy_employer_leads
                (vacancy_id, source_item_id, advertised_name, posting_url, site_hint, apply_hint, status, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (vacancy_id, advertised_name) DO UPDATE
                SET source_item_id=excluded.source_item_id, posting_url=excluded.posting_url,
                    status=excluded.status, reason=excluded.reason, site_hint=excluded.site_hint, apply_hint=excluded.apply_hint
                WHERE vacancy_employer_leads.status='SKIPPED' AND vacancy_employer_leads.source_item_id<>excluded.source_item_id
                """, row.vacancy(), row.item(), row.name(), row.url(), row.site(), row.apply(),
                relevant ? "PENDING" : "SKIPPED", reason);
    }

    public Claim claim(boolean searchAvailable) {
        // An abandoned last attempt stays inspectable even if its worker never returns.
        jdbc.update("""
                UPDATE vacancy_employer_leads SET status='MANUAL_REVIEW', reason='RETRY_EXHAUSTED',
                    claim_token=NULL, lease_until=NULL WHERE attempts=3 AND lease_until <= CURRENT_TIMESTAMP
                """);
        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM vacancy_employer_leads
                    WHERE (status IN ('PENDING','RETRYABLE_FAILURE') OR (status='WAITING_FOR_SEARCH' AND ?))
                        AND due_at <= CURRENT_TIMESTAMP AND attempts < 3
                        AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY due_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE vacancy_employer_leads l SET claim_token=?, lease_until=CURRENT_TIMESTAMP+interval '3 minutes',
                    attempts=attempts+1 FROM due WHERE due.id=l.id RETURNING l.*
                """, rs -> rs.next() ? new Claim(rs.getLong("id"), rs.getObject("claim_token", UUID.class),
                rs.getString("advertised_name"), rs.getString("posting_url"), rs.getString("site_hint"),
                rs.getString("apply_hint"), rs.getBoolean("needs_search")) : null, searchAvailable, UUID.randomUUID());
    }

    public boolean lock(Claim claim) {
        return !jdbc.queryForList("""
                SELECT id FROM vacancy_employer_leads WHERE id=? AND claim_token=? AND lease_until>CURRENT_TIMESTAMP
                FOR UPDATE
                """, Long.class, claim.id(), claim.token()).isEmpty();
    }

    public void finish(Claim claim, String outcome, String reason, Long registryId, String page) {
        jdbc.update("""
                UPDATE vacancy_employer_leads SET
                    status=CASE WHEN ?='RETRYABLE_FAILURE' AND attempts=3 THEN 'MANUAL_REVIEW' ELSE ? END,
                    needs_search=needs_search OR ?='WAITING_FOR_SEARCH', reason=?, registry_id=?, official_page_url=?, claim_token=NULL, lease_until=NULL,
                    attempts=attempts-CASE WHEN ?='WAITING_FOR_SEARCH' THEN 1 ELSE 0 END,
                    due_at=CURRENT_TIMESTAMP+interval '30 minutes' WHERE id=? AND claim_token=?
                """, outcome, outcome, outcome, reason, registryId, page, outcome, claim.id(), claim.token());
    }
}
