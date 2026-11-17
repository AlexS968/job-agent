package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class SourceLeadMigrationIT {
    @Test
    void upgradesRetainedLeadsWithoutLosingSearchProvenanceOrCrawlingAggregators() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("job_agent_lead_upgrade_test").withUsername("test_user")
                .withPassword("synthetic_test_secret_123")) {
            postgres.start();
            String url = postgres.getJdbcUrl();
            String user = postgres.getUsername();
            String password = postgres.getPassword();
            Flyway.configure().dataSource(url, user, password).target("22").load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password);
                 var statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO search_pilot_attempts (query_version, query_number, outcome)
                        VALUES ('synthetic-test', 1, 'SUCCESS')
                        """);
                statement.execute("""
                        INSERT INTO source_discovery_candidates
                        (candidate_url, first_search_attempt_id, last_search_attempt_id,
                         verification_outcome, verification_reason)
                        VALUES
                        ('https://wellfound.com/jobs/1', 1, 1, 'UNVERIFIED', NULL),
                        ('https://in.linkedin.com/jobs/2', 1, 1, 'RETRYABLE_FAILURE', 'HTTP_STATUS'),
                        ('https://acme.example/roles/3', 1, 1, 'UNVERIFIED', NULL),
                        ('https://job-boards.greenhouse.io/acme/jobs/4', 1, 1, 'UNVERIFIED', NULL),
                        ('https://en.devjobs.at/jobs/5', 1, 1, 'UNVERIFIED', NULL),
                        ('https://www.greenhouse.com/', 1, 1, 'UNVERIFIED', NULL),
                        ('https://tavily.com/', 1, 1, 'UNVERIFIED', NULL),
                        ('https://jobs.lever.co/acme/42', 1, 1, 'UNVERIFIED', NULL),
                        ('https://careers.greenhouse.com.example/jobs', 1, 1, 'UNVERIFIED', NULL),
                        ('https://job-boards.greenhouse.io/', 1, 1, 'UNVERIFIED', NULL),
                        ('https://jobs.lever.co/Bad_ID', 1, 1, 'UNVERIFIED', NULL)
                        """);
            }
            Flyway.configure().dataSource(url, user, password).load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password);
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("""
                         SELECT candidate_url, lead_kind, lead_priority, verification_outcome,
                                verification_reason, first_search_attempt_id, last_search_attempt_id
                         FROM source_discovery_candidates ORDER BY id
                         """)) {
                String[] kinds = {"AGGREGATOR", "AGGREGATOR", "EMPLOYER", "ATS", "AGGREGATOR",
                        "NON_EMPLOYER", "NON_EMPLOYER", "ATS", "EMPLOYER", "NON_EMPLOYER",
                        "NON_EMPLOYER"};
                int[] priorities = {2, 2, 1, 0, 2, 2, 2, 0, 1, 2, 2};
                for (int index = 0; index < kinds.length; index++) {
                    result.next();
                    assertEquals(kinds[index], result.getString("lead_kind"));
                    assertEquals(priorities[index], result.getInt("lead_priority"));
                    assertEquals(priorities[index] == 2 ? "MANUAL_REVIEW" : "UNVERIFIED",
                            result.getString("verification_outcome"));
                    if (priorities[index] == 2) {
                        assertEquals("AGGREGATOR".equals(kinds[index])
                                        ? "AGGREGATOR_ONLY_LEAD" : "NON_EMPLOYER_SITE",
                                result.getString("verification_reason"));
                    }
                    assertEquals(1, result.getLong("first_search_attempt_id"));
                    assertEquals(1, result.getLong("last_search_attempt_id"));
                }
                assertEquals(false, result.next());
            }
        }
    }
}
