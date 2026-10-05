package com.fdmultimedia.api.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class ReliabilityConfigurationTest {
    @Test
    void sessionSchemaIsFlywayOwnedAndSessionLifetimeIsBounded() throws IOException {
        assertThat(property("spring.session.jdbc.initialize-schema")).isEqualTo("never");
        assertThat(property("spring.session.timeout")).isEqualTo("${SESSION_TIMEOUT:30m}");
        assertThat(property("spring.session.jdbc.cleanup-cron")).isEqualTo("${SESSION_CLEANUP_CRON:0 * * * * *}");
    }

    @Test
    void livenessAndReadinessDependenciesAreSeparated() throws IOException {
        assertThat(property("management.endpoint.health.group.liveness.include")).isEqualTo("livenessState");
        assertThat(property("management.endpoint.health.group.readiness.include")).isEqualTo("readinessState,db");
        assertThat(property("management.endpoints.web.exposure.include")).isEqualTo("health");
    }

    @Test
    void gracefulShutdownAndPoolTimeoutsAreBounded() throws IOException {
        assertThat(property("server.shutdown")).isEqualTo("graceful");
        assertThat(property("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s");
        assertThat(property("spring.task.scheduling.shutdown.await-termination")).isEqualTo(true);
        assertThat(property("spring.datasource.hikari.connection-timeout")).isEqualTo(5000);
        assertThat(property("spring.datasource.hikari.validation-timeout")).isEqualTo(3000);
    }

    private Object property(String key) throws IOException {
        PropertySource<?> source = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml")).getFirst();
        return source.getProperty(key);
    }
}
