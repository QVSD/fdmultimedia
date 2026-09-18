package com.fdmultimedia.api.publishing.instagram;

import com.fdmultimedia.api.accounts.crypto.CredentialEncryptionService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Fails startup clearly when Instagram publishing is enabled but credential
 * encryption is not safely configured, instead of silently falling back to
 * plaintext storage or booting into a half-working state. TEST-only
 * deployments (the default) never construct this check's failure path since
 * {@code app.publishing.instagram.enabled} defaults to false.
 */
@Component
public class InstagramStartupCheck {

    private final InstagramProperties instagramProperties;
    private final CredentialEncryptionService credentialEncryptionService;

    public InstagramStartupCheck(InstagramProperties instagramProperties, CredentialEncryptionService credentialEncryptionService) {
        this.instagramProperties = instagramProperties;
        this.credentialEncryptionService = credentialEncryptionService;
    }

    @PostConstruct
    void validate() {
        if (!instagramProperties.isEnabled()) {
            return;
        }
        if (!credentialEncryptionService.isAvailable()) {
            throw new IllegalStateException(
                    "app.publishing.instagram.enabled is true but SOCIAL_CREDENTIAL_ENCRYPTION_KEY is not configured. "
                            + "Instagram credentials must never be stored in plaintext; set a base64-encoded 32-byte key "
                            + "or disable Instagram (INSTAGRAM_ENABLED=false).");
        }
        if (!instagramProperties.isConfigured()) {
            throw new IllegalStateException(
                    "app.publishing.instagram.enabled is true but META_APP_ID, META_APP_SECRET, or "
                            + "META_OAUTH_REDIRECT_URI is missing.");
        }
    }
}
