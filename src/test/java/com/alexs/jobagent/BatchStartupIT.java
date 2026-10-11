package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class BatchStartupIT {
    private static final String PASSWORD = "synthetic_test_secret_123";
    private static final Path JAR = Path.of("target/job-agent-0.1.0-SNAPSHOT.jar");

    @Test
    void emptyDatabaseAndRepeatStart() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("job_agent_test").withUsername("test_user").withPassword(PASSWORD)) {
            postgres.start();
            Result first = run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD);
            assertEquals(0, first.exitCode(), first.output());
            assertTrue(first.output().contains("Database ready; agent_runs rows: 0"), first.output());
            Result second = run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD);
            assertEquals(0, second.exitCode(), second.output());
            assertTrue(second.output().contains("Database ready; agent_runs rows: 0"), second.output());
            try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
            }
        }
    }

    @Test
    void unavailableDatabaseExitsWithErrorWithoutPassword() throws Exception {
        Result result = run("jdbc:postgresql://127.0.0.1:1/unavailable", "test_user", PASSWORD);
        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("Job Agent startup failed"), result.output());
        assertFalse(result.output().contains(PASSWORD), result.output());
    }

    @Test
    void explicitSyntheticModePersistsAndReplays() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("job_agent_test").withUsername("test_user").withPassword(PASSWORD)) {
            postgres.start();
            Result first = run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD, "--mode=synthetic");
            assertEquals(0, first.exitCode(), first.output());
            assertTrue(first.output().contains("source_created=true, vacancy_created=true"), first.output());
            Result second = run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD, "--mode=synthetic");
            assertEquals(0, second.exitCode(), second.output());
            assertTrue(second.output().contains("source_created=false, vacancy_created=false"), second.output());
            try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT (SELECT COUNT(*) FROM source_items), "
                         + "(SELECT COUNT(*) FROM vacancies), (SELECT COUNT(*) FROM agent_runs WHERE status = 'SUCCEEDED')")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals(1, rows.getInt(2));
                assertEquals(2, rows.getInt(3));
            }
        }
    }

    @Test
    void syntheticProcessingFailureExitsNonzeroWithoutPartialRowsOrSecrets() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("job_agent_test").withUsername("test_user").withPassword(PASSWORD)) {
            postgres.start();
            assertEquals(0, run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD).exitCode());
            try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE FUNCTION reject_vacancy() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "BEGIN RAISE EXCEPTION 'synthetic test failure'; END $$");
                statement.execute("CREATE TRIGGER reject_vacancy BEFORE INSERT ON vacancies "
                        + "FOR EACH ROW EXECUTE FUNCTION reject_vacancy()");
            }
            Result failed = run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD, "--mode=synthetic");
            assertNotEquals(0, failed.exitCode());
            assertTrue(failed.output().contains("Job Agent startup failed"), failed.output());
            assertFalse(failed.output().contains(PASSWORD), failed.output());
            try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT (SELECT COUNT(*) FROM source_items), "
                         + "(SELECT COUNT(*) FROM vacancies), (SELECT status FROM agent_runs LIMIT 1)")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
                assertEquals(0, rows.getInt(2));
                assertEquals("FAILED", rows.getString(3));
            }
        }
    }

    @Test
    void explicitDispatchCompletesPendingEventAndNormalStartupDoesNotDispatch() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("job_agent_dispatch_test").withUsername("test_user").withPassword(PASSWORD)) {
            postgres.start();
            assertEquals(0, run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD, "--mode=synthetic").exitCode());
            assertEquals(0, run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD).exitCode());
            try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT state FROM outbox_events")) {
                assertTrue(rows.next());
                assertEquals("PENDING", rows.getString(1));
            }
            Result dispatched = run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD, "--mode=dispatch");
            assertEquals(0, dispatched.exitCode(), dispatched.output());
            assertTrue(dispatched.output().contains("completed=1, deferred=0, failed=0"), dispatched.output());
            assertTrue(dispatched.output().contains("Backlog snapshot: ready=0, future_retries=0"), dispatched.output());
            Result repeated = run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD, "--mode=dispatch");
            assertEquals(0, repeated.exitCode(), repeated.output());
            assertTrue(repeated.output().contains("completed=0, deferred=0, failed=0"), repeated.output());
        }
    }

    @Test
    void invalidReplayModeExitsNonzeroWithoutSecrets() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("job_agent_replay_test").withUsername("test_user").withPassword(PASSWORD)) {
            postgres.start();
            Result result = run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD,
                    "--mode=replay-failed", "--event-id=00000000-0000-0000-0000-000000000001");
            assertNotEquals(0, result.exitCode());
            assertFalse(result.output().contains(PASSWORD));
        }
    }

    @Test
    void exhaustedDispatchReturnsNonzeroAndDeferredRetriesRemainPending() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("job_agent_failed_dispatch_test").withUsername("test_user").withPassword(PASSWORD)) {
            postgres.start();
            assertEquals(0, run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD, "--mode=synthetic").exitCode());
            try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE outbox_events SET event_type = 'Unsupported'");
            }
            for (int attempt = 1; attempt <= 3; attempt++) {
                Result result = run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD, "--mode=dispatch");
                assertEquals(attempt == 3 ? 1 : 0, result.exitCode(), result.output());
                assertTrue(result.output().contains(attempt == 3 ? "failed=1" : "deferred=1"), result.output());
                assertTrue(result.output().contains(attempt == 3 ? "terminal_failures=1" : "future_retries=1"),
                        result.output());
                assertFalse(result.output().contains(PASSWORD));
                if (attempt < 3) {
                    try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD);
                         var statement = connection.createStatement()) {
                        statement.executeUpdate("UPDATE outbox_events SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
                    }
                }
            }
            String eventId;
            try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT event_id FROM outbox_events")) {
                assertTrue(rows.next());
                eventId = rows.getString(1);
            }
            Result replayed = run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD,
                    "--mode=replay-failed", "--event-id=" + eventId);
            assertEquals(0, replayed.exitCode(), replayed.output());
            try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE outbox_events SET event_type = 'VacancyDiscovered'");
            }
            assertEquals(0, run(postgres.getJdbcUrl(), postgres.getUsername(), PASSWORD, "--mode=dispatch").exitCode());
        }
    }

    private static Result run(String url, String username, String password, String... arguments)
            throws IOException, InterruptedException {
        Path outputFile = Files.createTempFile("job-agent-batch-", ".log");
        String jacocoAgent = System.getProperty("jacoco.agent.argLine");
        if (jacocoAgent == null || !jacocoAgent.startsWith("-javaagent:")) {
            Files.deleteIfExists(outputFile);
            throw new IllegalStateException("JaCoCo agent configuration is missing for the batch JVM");
        }
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add(jacocoAgent);
        command.add("-jar");
        command.add(JAR.toString());
        command.addAll(java.util.List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(outputFile.toFile());
        Map<String, String> env = builder.environment();
        env.put("DB_URL", url);
        env.put("DB_USER", username);
        env.put("DB_PASSWORD", password);
        Process process = null;
        try {
            process = builder.start();
            if (!process.waitFor(Duration.ofSeconds(40).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor();
                fail("Batch process timed out. Output:\n" + Files.readString(outputFile, StandardCharsets.UTF_8));
            }
            return new Result(process.exitValue(), Files.readString(outputFile, StandardCharsets.UTF_8));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
            Files.deleteIfExists(outputFile);
        }
    }

    private record Result(int exitCode, String output) {}
}
