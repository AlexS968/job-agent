package com.alexs.jobagent.evaluation.repository;

import com.alexs.jobagent.evaluation.dto.ExtractionBudgetDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionCompletionDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionWorkIdentityDTO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExtractionRepository {
    private final JdbcTemplate jdbc;

    public ExtractionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BudgetRow lockBudget() {
        return jdbc.queryForObject("SELECT reserved_micro_usd, observed_micro_usd, attempts, primary_distinct, "
                + "comparison_distinct, active_work_id, active_claim_token, halted_reason "
                + "FROM extraction_pilot_budget WHERE id = 1 FOR UPDATE", (rs, row) -> new BudgetRow(
                rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                rs.getObject(6, Long.class), rs.getObject(7, UUID.class), rs.getString(8)));
    }

    public WorkRow find(ExtractionWorkIdentityDTO identity) {
        return jdbc.query("SELECT id, status, result_json::text, claim_token, lease_until < CURRENT_TIMESTAMP, "
                + "input_json::text "
                + "FROM extraction_work WHERE vacancy_id = ? AND source_item_id = ? AND vacancy_revision = ? "
                + "AND provider = 'ANTHROPIC' AND model = ? AND prompt_version = ? AND schema_version = ? "
                + "AND policy_version = ? FOR UPDATE", (rs, row) -> work(rs), identity.vacancyId(),
                identity.sourceItemId(), identity.revision(), identity.model(), identity.promptVersion(),
                identity.schemaVersion(), identity.policyVersion()).stream().findFirst().orElse(null);
    }

    public boolean primarySucceeded(ExtractionWorkIdentityDTO identity, String inputJson) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM extraction_work "
                + "WHERE vacancy_id = ? AND source_item_id = ? AND vacancy_revision = ? AND model = ? "
                + "AND prompt_version = ? AND schema_version = ? AND policy_version = ? "
                + "AND input_json = CAST(? AS jsonb) AND kind = 'PRIMARY' AND status = 'SUCCEEDED')",
                Boolean.class, identity.vacancyId(), identity.sourceItemId(), identity.revision(),
                identity.model(), identity.promptVersion(), identity.schemaVersion(), identity.policyVersion(), inputJson));
    }

    public long insertWork(ExtractionWorkIdentityDTO identity, String kind, String inputJson, UUID token) {
        return jdbc.queryForObject("INSERT INTO extraction_work (vacancy_id, source_item_id, vacancy_revision, "
                + "provider, model, prompt_version, schema_version, policy_version, input_json, kind, status, claim_token, lease_until) "
                + "VALUES (?, ?, ?, 'ANTHROPIC', ?, ?, ?, ?, CAST(? AS jsonb), ?, 'CLAIMED', ?, "
                + "CURRENT_TIMESTAMP + INTERVAL '60 seconds') "
                + "RETURNING id", Long.class, identity.vacancyId(), identity.sourceItemId(),
                identity.revision(), identity.model(), identity.promptVersion(), identity.schemaVersion(),
                identity.policyVersion(), inputJson, kind, token);
    }

    public void claimAgain(long workId, UUID token) {
        jdbc.update("UPDATE extraction_work SET status = 'CLAIMED', claim_token = ?, "
                + "lease_until = CURRENT_TIMESTAMP + INTERVAL '60 seconds', updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ?", token, workId);
    }

    public int attemptCount(long workId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM extraction_attempts WHERE work_id = ?", Integer.class, workId);
    }

    public void reserve(long workId, UUID token, long amount, boolean primary, boolean distinct) {
        int changed = jdbc.update("UPDATE extraction_pilot_budget SET reserved_micro_usd = reserved_micro_usd + ?, "
                + "attempts = attempts + 1, primary_distinct = primary_distinct + ?, "
                + "comparison_distinct = comparison_distinct + ?, active_work_id = ?, "
                + "active_claim_token = ?, active_since = CURRENT_TIMESTAMP "
                + "WHERE id = 1 AND active_work_id IS NULL AND halted_reason IS NULL",
                amount, distinct && primary ? 1 : 0, distinct && !primary ? 1 : 0, workId, token);
        if (changed != 1) {
            throw new IllegalStateException("Extraction pilot has active or halted work");
        }
        jdbc.update("INSERT INTO extraction_attempts (work_id, claim_token, reserved_micro_usd, outcome) "
                + "VALUES (?, ?, ?, 'RESERVED')", workId, token, amount);
    }

    public void finish(ExtractionCompletionDTO completion) {
        long workId = completion.workId();
        UUID token = completion.claimToken();
        var usage = completion.usage();
        Long observedCost = usage.observedCostMicroUsd();
        int changed = jdbc.update("UPDATE extraction_work SET status = ?, result_json = CAST(? AS jsonb), "
                + "claim_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND claim_token = ? AND status = 'CLAIMED' "
                + "AND lease_until >= CURRENT_TIMESTAMP", completion.status(), completion.resultJson(), workId, token);
        if (changed != 1) {
            throw new IllegalStateException("Extraction claim changed before completion");
        }
        int attemptsChanged = jdbc.update("UPDATE extraction_attempts SET outcome = ?, input_tokens = ?, "
                + "cache_creation_input_tokens = ?, cache_read_input_tokens = ?, output_tokens = ?, "
                + "observed_cost_micro_usd = ?, provider_request_id = ?, finished_at = CURRENT_TIMESTAMP "
                + "WHERE work_id = ? AND claim_token = ?", completion.outcome(), usage.inputTokens(),
                usage.cacheCreationTokens(), usage.cacheReadTokens(), usage.outputTokens(), observedCost,
                usage.requestId(), workId, token);
        if (attemptsChanged != 1) {
            throw new IllegalStateException("Extraction attempt changed before completion");
        }
        boolean uncertain = "UNCERTAIN".equals(completion.status());
        boolean overEstimate = observedCost != null && observedCost > completion.reservedMicroUsd();
        long delta = observedCost == null ? 0 : observedCost - completion.reservedMicroUsd();
        int budgetChanged = jdbc.update("UPDATE extraction_pilot_budget SET "
                + "reserved_micro_usd = reserved_micro_usd + ?, "
                + "observed_micro_usd = observed_micro_usd + ?, "
                + "halted_reason = CASE WHEN ? THEN 'USAGE_OVER_RESERVATION' ELSE halted_reason END, "
                + "active_work_id = CASE WHEN ? THEN active_work_id ELSE NULL END, "
                + "active_claim_token = CASE WHEN ? THEN active_claim_token ELSE NULL END, "
                + "active_since = CASE WHEN ? THEN active_since ELSE NULL END "
                + "WHERE id = 1 AND active_work_id = ? AND active_claim_token = ?",
                delta, observedCost == null ? 0 : observedCost, overEstimate,
                uncertain, uncertain, uncertain, workId, token);
        if (budgetChanged != 1) {
            throw new IllegalStateException("Global extraction claim changed before completion");
        }
    }

    public void recover(long workId, UUID token) {
        int released = jdbc.update("UPDATE extraction_pilot_budget SET active_work_id = NULL, "
                + "active_claim_token = NULL, active_since = NULL WHERE id = 1 "
                + "AND active_work_id = ? AND active_claim_token = ? "
                + "AND active_since < CURRENT_TIMESTAMP - INTERVAL '60 seconds'", workId, token);
        if (released != 1) {
            throw new IllegalStateException("Global extraction claim is not eligible for recovery");
        }
        int workChanged = jdbc.update("UPDATE extraction_work SET status = 'UNCERTAIN', claim_token = NULL, "
                + "lease_until = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ? "
                + "AND status IN ('CLAIMED', 'UNCERTAIN')", workId);
        if (workChanged != 1) {
            throw new IllegalStateException("Recovery work state changed");
        }
        jdbc.update("UPDATE extraction_attempts SET outcome = 'UNCERTAIN', finished_at = CURRENT_TIMESTAMP "
                + "WHERE work_id = ? AND outcome = 'RESERVED'", workId);
    }

    public ExtractionBudgetDTO budget() {
        return jdbc.queryForObject("SELECT b.reserved_micro_usd, b.observed_micro_usd, b.attempts, "
                + "b.primary_distinct, b.comparison_distinct, b.active_work_id, b.halted_reason, "
                + "COUNT(*) FILTER (WHERE w.status = 'CLAIMED'), "
                + "COUNT(*) FILTER (WHERE w.status = 'SUCCEEDED'), "
                + "COUNT(*) FILTER (WHERE w.status = 'FAILED'), "
                + "COUNT(*) FILTER (WHERE w.status = 'UNCERTAIN'), "
                + "COUNT(*) FILTER (WHERE w.status = 'CLAIMED' AND w.lease_until < CURRENT_TIMESTAMP) "
                + "FROM extraction_pilot_budget b LEFT JOIN extraction_work w ON TRUE "
                + "WHERE b.id = 1 GROUP BY b.reserved_micro_usd, b.observed_micro_usd, b.attempts, "
                + "b.primary_distinct, b.comparison_distinct, b.active_work_id, b.halted_reason",
                (rs, row) -> new ExtractionBudgetDTO(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getInt(4),
                rs.getInt(5), rs.getObject(6, Long.class), rs.getString(7), rs.getInt(8), rs.getInt(9),
                rs.getInt(10), rs.getInt(11), rs.getInt(12)));
    }

    private static WorkRow work(ResultSet rs) throws SQLException {
        return new WorkRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getBoolean(5), rs.getString(6));
    }

    public record BudgetRow(long reservedMicroUsd, long observedMicroUsd, int attempts, int primaryDistinct,
                            int comparisonDistinct, Long activeWorkId, UUID activeToken,
                            String haltedReason) { }
    public record WorkRow(long id, String status, String resultJson, UUID claimToken, boolean expired,
                          String inputJson) { }
}
