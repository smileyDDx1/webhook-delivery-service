package com.debangshu.webhooks;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real PostgreSQL in Docker for the integration tests. I don't use H2 because the code depends on
 * Postgres-only SQL (SKIP LOCKED, ON CONFLICT, text[], percentile_cont) that H2 doesn't support.
 *
 * @ServiceConnection makes Spring Boot point the datasource at this container automatically.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }
}
