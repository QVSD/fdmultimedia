package com.fdmultimedia.api.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationValidatorTest {
    private static MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("spring.datasource.password", "Zk3pQ8vLm2Xr9TnA")
                .withProperty("app.storage.access-key", "pilot-access-key-7f3a")
                .withProperty("app.storage.secret-key", "Hq8Wm2Pz5Lc9Vn4Ty7Bd")
                .withProperty("app.storage.endpoint", "http://minio:9000")
                .withProperty("app.storage.public-endpoint", "https://storage.pilot.example.org")
                .withProperty("app.public-origin", "https://app.pilot.example.org")
                .withProperty("server.servlet.session.cookie.secure", "true")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate");
    }

    private static List<String> problems(MockEnvironment env) { return ProductionConfigurationValidator.validate(env); }

    @Test
    void aCompleteConfigurationHasNoProblems() {
        assertThat(problems(valid())).isEmpty();
    }

    @Test
    void missingCriticalValuesAreAllReported() {
        List<String> found = problems(new MockEnvironment());
        assertThat(found).anyMatch(p -> p.startsWith("DB_PASSWORD"))
                .anyMatch(p -> p.startsWith("STORAGE_ACCESS_KEY")).anyMatch(p -> p.startsWith("STORAGE_SECRET_KEY"))
                .anyMatch(p -> p.startsWith("APP_PUBLIC_ORIGIN")).anyMatch(p -> p.startsWith("STORAGE_PUBLIC_ENDPOINT"))
                .anyMatch(p -> p.contains("cookie.secure"));
    }

    @Test
    void developmentPlaceholdersAreRejected() {
        MockEnvironment env = valid().withProperty("spring.datasource.password", "dev_password_change_me")
                .withProperty("app.storage.secret-key", "dev_minio_password_change_me");
        assertThat(problems(env)).anyMatch(p -> p.startsWith("DB_PASSWORD") && p.contains("placeholder"))
                .anyMatch(p -> p.startsWith("STORAGE_SECRET_KEY") && p.contains("placeholder"));
    }

    @Test
    void shortSecretsAreRejected() {
        assertThat(problems(valid().withProperty("spring.datasource.password", "Ab1!x"))).anyMatch(p -> p.contains("at least 12"));
    }

    @Test
    void messagesNeverContainTheConfiguredValues() {
        MockEnvironment env = valid().withProperty("spring.datasource.password", "dev_password_change_me")
                .withProperty("app.public-origin", "http://secret-host.example.org/with/path?token=abc");
        String all = String.join("\n", problems(env));
        assertThat(all).doesNotContain("dev_password_change_me").doesNotContain("secret-host").doesNotContain("token=abc");
    }

    @Test
    void publicOriginMustBeHttpsOrLoopback() {
        assertThat(problems(valid().withProperty("app.public-origin", "http://app.pilot.example.org"))).anyMatch(p -> p.contains("must be https"));
        assertThat(problems(valid().withProperty("app.public-origin", "http://localhost:8080")
                .withProperty("app.storage.public-endpoint", "http://storage.localhost:9000"))).isEmpty();
        assertThat(problems(valid().withProperty("app.public-origin", "https://app.example.org/path"))).anyMatch(p -> p.contains("origin only"));
        assertThat(problems(valid().withProperty("app.public-origin", "not a url"))).isNotEmpty();
        assertThat(problems(valid().withProperty("app.public-origin", "ftp://app.example.org"))).isNotEmpty();
    }

    @Test
    void httpsApplicationRequiresHttpsStorageToAvoidMixedContent() {
        assertThat(problems(valid().withProperty("app.storage.public-endpoint", "http://localhost:9000")))
                .anyMatch(p -> p.contains("mixed content"));
    }

    @Test
    void insecureCookiesAndSchemaMutationAreRejected() {
        assertThat(problems(valid().withProperty("server.servlet.session.cookie.secure", "false"))).anyMatch(p -> p.contains("cookie.secure"));
        assertThat(problems(valid().withProperty("spring.jpa.hibernate.ddl-auto", "update"))).anyMatch(p -> p.contains("ddl-auto"));
    }

    @Test
    void enabledPublishersRequireTheirCredentialsAndHttpsCallbacks() {
        MockEnvironment env = valid().withProperty("app.publishing.instagram.enabled", "true");
        assertThat(problems(env)).anyMatch(p -> p.startsWith("SOCIAL_CREDENTIAL_ENCRYPTION_KEY")).anyMatch(p -> p.startsWith("META_APP_ID"))
                .anyMatch(p -> p.startsWith("META_APP_SECRET")).anyMatch(p -> p.startsWith("META_OAUTH_REDIRECT_URI"))
                .anyMatch(p -> p.startsWith("META_PUBLIC_BASE_URL"));
        env.withProperty("app.social-credentials.encryption-key", "k").withProperty("app.publishing.instagram.app-id", "1")
                .withProperty("app.publishing.instagram.app-secret", "s").withProperty("app.publishing.instagram.oauth-redirect-uri", "http://x/cb")
                .withProperty("app.publishing.instagram.public-base-url", "https://app.pilot.example.org");
        assertThat(problems(env)).containsExactly("META_OAUTH_REDIRECT_URI must be an https URL");
        MockEnvironment tiktok = valid().withProperty("app.publishing.tiktok.enabled", "true");
        assertThat(problems(tiktok)).anyMatch(p -> p.startsWith("TIKTOK_CLIENT_KEY")).anyMatch(p -> p.startsWith("TIKTOK_REDIRECT_URI"));
    }

    @Test
    void disabledPublishersNeedNothing() {
        assertThat(problems(valid().withProperty("app.publishing.instagram.enabled", "false"))).isEmpty();
    }

    @Test
    void developmentBootstrapVariablesAreRefusedInProduction() {
        assertThat(problems(valid().withProperty("app.bootstrap.admin.password", "x"))).anyMatch(p -> p.contains("development-only"));
    }

    @Test
    void canonicalOriginNormalisesCaseAndDefaultPorts() {
        assertThat(ProductionConfigurationValidator.canonicalOrigin("HTTPS://App.Example.org:443/")).isEqualTo("https://app.example.org");
        assertThat(ProductionConfigurationValidator.canonicalOrigin("http://localhost:8080")).isEqualTo("http://localhost:8080");
        assertThat(ProductionConfigurationValidator.canonicalOrigin("http://localhost:80")).isEqualTo("http://localhost");
        assertThat(ProductionConfigurationValidator.canonicalOrigin("null")).isEmpty();
        assertThat(ProductionConfigurationValidator.canonicalOrigin("")).isEmpty();
    }
}
