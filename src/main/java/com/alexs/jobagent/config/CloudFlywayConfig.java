package com.alexs.jobagent.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("cloud-app")
public class CloudFlywayConfig {
    @Bean
    FlywayMigrationStrategy validateOnlyMigrations() {
        return Flyway::validate;
    }
}
