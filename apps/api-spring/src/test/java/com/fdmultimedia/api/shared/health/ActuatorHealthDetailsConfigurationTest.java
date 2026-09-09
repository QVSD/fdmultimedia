package com.fdmultimedia.api.shared.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class ActuatorHealthDetailsConfigurationTest {

    private static final String SHOW_DETAILS = "management.endpoint.health.show-details";

    @Test
    void defaultConfigurationDoesNotExposeHealthDetails() throws IOException {
        assertThat(loadProperty("application.yml", SHOW_DETAILS)).isEqualTo("never");
    }

    @Test
    void devConfigurationExplicitlyExposesHealthDetails() throws IOException {
        assertThat(loadProperty("application-dev.yml", SHOW_DETAILS)).isEqualTo("always");
    }

    @Test
    void prodConfigurationDoesNotExposeHealthDetails() throws IOException {
        assertThat(loadProperty("application-prod.yml", SHOW_DETAILS)).isEqualTo("never");
    }

    private static Object loadProperty(String resourceName, String propertyName) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        PropertySource<?> propertySource = loader.load(resourceName, new ClassPathResource(resourceName)).getFirst();
        return propertySource.getProperty(propertyName);
    }
}
