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
                assertEquals(1, rows.getInt(1));
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

    private static Result run(String url, String username, String password) throws IOException, InterruptedException {
        Path outputFile = Files.createTempFile("job-agent-batch-", ".log");
        String jacocoAgent = System.getProperty("jacoco.agent.argLine");
        if (jacocoAgent == null || !jacocoAgent.startsWith("-javaagent:")) {
            Files.deleteIfExists(outputFile);
            throw new IllegalStateException("JaCoCo agent configuration is missing for the batch JVM");
        }
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                jacocoAgent, "-jar", JAR.toString());
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
