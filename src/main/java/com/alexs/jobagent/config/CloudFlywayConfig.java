package com.alexs.jobagent.config;

import javax.sql.DataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("cloud-app")
public class CloudFlywayConfig {
    @Bean
    FlywayMigrationStrategy validateOnlyMigrations(DataSource dataSource) {
        return flyway -> {
            new StartupConnectionRetry(dataSource).awaitConnection();
            flyway.validate();
        };
    }
}
