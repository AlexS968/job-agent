package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class OutboxMigrationIT {
    @Test
    void upgradesRetainedV2DataWithoutChangingIt() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("job_agent_upgrade_test").withUsername("test_user")
                .withPassword("synthetic_test_secret_123")) {
            postgres.start();
            String url = postgres.getJdbcUrl();
            String user = postgres.getUsername();
            String password = postgres.getPassword();
            Flyway.configure().dataSource(url, user, password).target("2").load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password);
                 var statement = connection.createStatement()) {
                statement.execute("INSERT INTO agent_runs(status) VALUES ('SUCCEEDED')");
                statement.execute("INSERT INTO employers(source_namespace, external_id, name) "
                        + "VALUES ('synthetic', 'employer-1', 'Example Works')");
                statement.execute("INSERT INTO vacancies(employer_id, source_namespace, listing_id, title, location) "
                        + "VALUES (1, 'synthetic', 'listing-1', 'Engineer', 'Remote')");
            }
            Flyway.configure().dataSource(url, user, password).load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password);
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT (SELECT COUNT(*) FROM flyway_schema_history "
                         + "WHERE success), (SELECT COUNT(*) FROM vacancies), "
                         + "(SELECT COUNT(*) FROM outbox_events)")) {
                result.next();
                assertEquals(5, result.getInt(1));
                assertEquals(1, result.getInt(2));
                assertEquals(0, result.getInt(3));
            }
        }
    }
}
