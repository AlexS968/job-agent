package com.alexs.jobagent;

import org.flywaydb.core.Flyway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
public class JobAgentApplication {
    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = SpringApplication.run(JobAgentApplication.class, args)) {
            // Closing the context releases the connection pool before the batch process exits.
        } catch (Exception exception) {
            System.err.println("Job Agent startup failed; check database availability and configuration.");
            System.exit(1);
        }
    }

    @Bean
    CommandLineRunner verifyDatabase(Flyway flyway, JdbcTemplate jdbc) {
        return args -> {
            flyway.validate();
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs", Integer.class);
            System.out.println("Database ready; agent_runs rows: " + count);
        };
    }
}
