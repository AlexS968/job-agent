package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class SourceDecisionMigrationIT {
    @Test
    void upgradesV7EvidenceAndDeactivatesSourceContradictedByNewerObservation() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("job_agent_source_upgrade_test").withUsername("test_user")
                .withPassword("synthetic_test_secret_123")) {
            postgres.start();
            String url = postgres.getJdbcUrl();
            String user = postgres.getUsername();
            String password = postgres.getPassword();
            Flyway.configure().dataSource(url, user, password).target("7").load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password);
                 var statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO verified_source_registry
                        (platform, region, source_id, employer_domain, employer_name, official_page_url,
                         evidence_url, evidence_hash, verified_at, status, active)
                        VALUES ('GREENHOUSE', 'global', 'acme', 'acme.example', 'Acme Synthetic',
                                'https://acme.example/careers', 'https://job-boards.greenhouse.io/acme',
                                repeat('a', 64), '2026-10-26 12:00:00+00', 'VERIFIED', TRUE)
                        """);
                statement.execute("""
                        INSERT INTO source_verification_attempts
                        (candidate_url, origin, claimed_employer_domain, official_page_url, observed_at,
                         method_version, outcome, reason, evidence_hash, requests)
                        VALUES ('https://job-boards.greenhouse.io/acme', 'owner-confirmed',
                                'acme.example', 'https://acme.example/careers',
                                '2026-10-26 12:00:01+00', 'source-verifier-v1', 'MANUAL_REVIEW',
                                'NO_SUPPORTED_LINK', repeat('b', 64), 1),
                               ('https://other.example/careers', 'owner-confirmed',
                                'other.example', 'https://other.example/careers',
                                '2026-10-26 12:00:02+00', 'source-verifier-v1', 'UNSUPPORTED',
                                'UNSUPPORTED_PLATFORM', repeat('c', 64), 1)
                        """);
            }
            Flyway.configure().dataSource(url, user, password).load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password);
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("""
                         SELECT source.status, source.active, decision.decision,
                                decision.associated_platform, decision.associated_source_id,
                                (SELECT count(*) FROM source_verification_decisions)
                         FROM verified_source_registry source
                         JOIN source_verification_decisions decision
                           ON source.employer_domain = decision.employer_domain
                         """)) {
                result.next();
                assertEquals("UNVERIFIED", result.getString(1));
                assertFalse(result.getBoolean(2));
                assertEquals("UNVERIFIED", result.getString(3));
                assertEquals("GREENHOUSE", result.getString(4));
                assertEquals("acme", result.getString(5));
                assertEquals(2, result.getInt(6));
            }
        }
    }
}
