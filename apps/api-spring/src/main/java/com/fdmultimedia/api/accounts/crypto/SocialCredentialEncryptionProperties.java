package com.fdmultimedia.api.accounts.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment-supplied master key for {@link CredentialEncryptionService}. The
 * key is never generated automatically and never stored in the database —
 * only ever read from configuration/environment.
 */
@ConfigurationProperties(prefix = "app.social-credentials")
public class SocialCredentialEncryptionProperties {

    /** Base64-encoded 256-bit (32-byte) AES-GCM key. Blank when unconfigured. */
    private String encryptionKey = "";

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey == null ? "" : encryptionKey;
    }
}
