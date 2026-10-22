package com.alexs.jobagent.run.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import com.alexs.jobagent.run.dto.RunResultDTO;
import org.springframework.stereotype.Repository;

@Repository
public class AgentRunRepository {
    private final JdbcTemplate jdbc;

    public AgentRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long start() {
        return jdbc.queryForObject("INSERT INTO agent_runs(status) VALUES ('RUNNING') RETURNING id", Long.class);
    }

    public void succeed(long runId, boolean created) {
        int changed = jdbc.update("UPDATE agent_runs SET status = 'SUCCEEDED', result = ?, finished_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'RUNNING'", created ? "INGESTED" : "REPLAYED", runId);
        if (changed != 1) {
            throw new IllegalStateException("Run state transition failed");
        }
    }

    public void succeedLive(long runId, RunResultDTO change) {
        int changed = jdbc.update("UPDATE agent_runs SET status = 'SUCCEEDED', result = ?, "
                        + "finished_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'RUNNING'",
                change.name(), runId);
        if (changed != 1) {
            throw new IllegalStateException("Run state transition failed");
        }
    }

    public void fail(long runId, String errorCode) {
        int changed = jdbc.update("UPDATE agent_runs SET status = 'FAILED', error_code = ?, finished_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'RUNNING'", errorCode, runId);
        if (changed != 1) {
            throw new IllegalStateException("Run state transition failed");
        }
    }
}
