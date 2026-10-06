package com.fdmultimedia.api.release;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Fail-closed validation of the configuration a production or pilot deployment must provide. It reports <em>variable names and the
 * reason only</em>, never a configured value, so a startup failure can be pasted into a ticket without leaking a secret.
 *
 * <p>Development defaults are rejected explicitly: the most common real-world incident is a copied {@code .env} that still carries
 * the placeholder passwords from the development example.
 */
public final class ProductionConfigurationValidator {
    static final int MIN_SECRET_LENGTH = 12;
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final List<String> KNOWN_PLACEHOLDERS = List.of("change_me", "changeme", "dev_password", "dev_minio", "dev_owner",
            "dev_admin", "dev_worker", "password", "example");

    private ProductionConfigurationValidator() {}

    public static List<String> validate(Environment env) {
        List<String> problems = new ArrayList<>();
        secret(problems, "DB_PASSWORD", env.getProperty("spring.datasource.password"));
        secret(problems, "STORAGE_ACCESS_KEY", env.getProperty("app.storage.access-key"));
        secret(problems, "STORAGE_SECRET_KEY", env.getProperty("app.storage.secret-key"));

        String origin = env.getProperty("app.public-origin", "");
        boolean originOk = origin(problems, "APP_PUBLIC_ORIGIN", origin);

        String storagePublic = env.getProperty("app.storage.public-endpoint", "");
        String storageInternal = env.getProperty("app.storage.endpoint", "");
        if (storagePublic.isBlank()) {
            problems.add("STORAGE_PUBLIC_ENDPOINT is required (the origin browsers and Workers use for presigned URLs)");
        } else if (origin(problems, "STORAGE_PUBLIC_ENDPOINT", storagePublic) && originOk && isHttps(origin) && !isHttps(storagePublic)) {
            problems.add("STORAGE_PUBLIC_ENDPOINT must be https when APP_PUBLIC_ORIGIN is https (browsers block mixed content)");
        }
        if (storageInternal.isBlank()) {
            problems.add("STORAGE_ENDPOINT is required");
        }

        if (!env.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            problems.add("server.servlet.session.cookie.secure must be true in the production profile");
        }
        String ddl = env.getProperty("spring.jpa.hibernate.ddl-auto", "none");
        if (!ddl.equalsIgnoreCase("validate") && !ddl.equalsIgnoreCase("none")) {
            problems.add("spring.jpa.hibernate.ddl-auto must be validate or none; Flyway owns the schema");
        }

        publisher(problems, env, "INSTAGRAM", "app.publishing.instagram", true);
        publisher(problems, env, "TIKTOK", "app.publishing.tiktok", false);

        for (String developmentOnly : List.of("app.bootstrap.admin.password", "app.bootstrap.second-user.password",
                "app.bootstrap.worker-credential.secret")) {
            if (!env.getProperty(developmentOnly, "").isBlank()) {
                problems.add("BOOTSTRAP_* variables are development-only and are not used in production; use FDM_OWNER_* and"
                        + " FDM_WORKER_CREDENTIAL_* (see docs/CONFIGURATION.md)");
                break;
            }
        }
        return problems;
    }

    private static void publisher(List<String> problems, Environment env, String label, String prefix, boolean needsAppId) {
        if (!env.getProperty(prefix + ".enabled", Boolean.class, false)) return;
        if (env.getProperty("app.social-credentials.encryption-key", "").isBlank()) {
            problems.add("SOCIAL_CREDENTIAL_ENCRYPTION_KEY is required when " + label + "_ENABLED=true");
        }
        String clientId = env.getProperty(needsAppId ? prefix + ".app-id" : prefix + ".client-key", "");
        String clientSecret = env.getProperty(needsAppId ? prefix + ".app-secret" : prefix + ".client-secret", "");
        String idName = needsAppId ? "META_APP_ID" : "TIKTOK_CLIENT_KEY";
        String secretName = needsAppId ? "META_APP_SECRET" : "TIKTOK_CLIENT_SECRET";
        if (clientId.isBlank()) problems.add(idName + " is required when " + label + "_ENABLED=true");
        if (clientSecret.isBlank()) problems.add(secretName + " is required when " + label + "_ENABLED=true");
        String redirect = env.getProperty(prefix + ".oauth-redirect-uri", "");
        String redirectName = needsAppId ? "META_OAUTH_REDIRECT_URI" : "TIKTOK_REDIRECT_URI";
        if (redirect.isBlank()) {
            problems.add(redirectName + " is required when " + label + "_ENABLED=true");
        } else if (!isHttps(redirect)) {
            problems.add(redirectName + " must be an https URL");
        }
        if (needsAppId) {
            String publicBase = env.getProperty(prefix + ".public-base-url", "");
            if (publicBase.isBlank()) problems.add("META_PUBLIC_BASE_URL is required when INSTAGRAM_ENABLED=true");
            else if (!isHttps(publicBase)) problems.add("META_PUBLIC_BASE_URL must be an https origin reachable by Meta");
        }
    }

    private static void secret(List<String> problems, String name, String value) {
        if (value == null || value.isBlank()) {
            problems.add(name + " is required");
            return;
        }
        if (value.length() < MIN_SECRET_LENGTH) {
            problems.add(name + " must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String placeholder : KNOWN_PLACEHOLDERS) {
            if (lower.contains(placeholder)) {
                problems.add(name + " still looks like a development placeholder; generate a real secret");
                return;
            }
        }
    }

    /** Validates an absolute origin (scheme, host, optional port, no path). Plain http is accepted only for loopback evaluation. */
    private static boolean origin(List<String> problems, String name, String value) {
        if (value == null || value.isBlank()) {
            problems.add(name + " is required");
            return false;
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException ex) {
            problems.add(name + " is not a valid URL");
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null) {
            problems.add(name + " must be an absolute http(s) origin");
            return false;
        }
        if ((uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")) || uri.getQuery() != null || uri.getFragment() != null
                || uri.getUserInfo() != null) {
            problems.add(name + " must be an origin only (scheme, host, optional port; no path, query or credentials)");
            return false;
        }
        if (scheme.equals("http") && !LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)) && !uri.getHost().toLowerCase(Locale.ROOT).endsWith(".localhost")) {
            problems.add(name + " must be https (plain http is accepted only for loopback evaluation on localhost)");
            return false;
        }
        return true;
    }

    private static boolean isHttps(String value) {
        return value != null && value.trim().toLowerCase(Locale.ROOT).startsWith("https://");
    }

    /** Canonical form used for Origin comparison: lower-case scheme and host, default ports removed, no trailing slash. */
    public static String canonicalOrigin(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value.trim());
            if (uri.getScheme() == null || uri.getHost() == null) return "";
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }
}
