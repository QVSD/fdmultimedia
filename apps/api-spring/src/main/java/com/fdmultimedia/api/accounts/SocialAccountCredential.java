package com.fdmultimedia.api.accounts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The credential-storage boundary Phase 10A deferred. Holds only what a real
 * provider integration actually needs, and only in encrypted form —
 * {@link #encryptedAccessToken} is ciphertext produced by
 * {@code CredentialEncryptionService}, never a plaintext token. This entity
 * is never serialized to the browser; see {@code SocialCredentialService} for
 * the narrow interface controllers/services are allowed to use.
 */
@Entity
@Table(name = "social_account_credentials")
public class SocialAccountCredential {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "social_account_id", nullable = false, unique = true)
    private SocialAccount socialAccount;

    @Column(name = "credential_type", nullable = false)
    private String credentialType;

    @Column(name = "encrypted_access_token", nullable = false)
    private String encryptedAccessToken;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column
    private String scopes;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SocialAccountCredential() {
    }

    public SocialAccountCredential(
            SocialAccount socialAccount,
            String credentialType,
            String encryptedAccessToken,
            Instant tokenExpiresAt,
            String scopes,
            Instant now) {
        this.id = UUID.randomUUID();
        this.socialAccount = socialAccount;
        this.credentialType = credentialType;
        this.encryptedAccessToken = encryptedAccessToken;
        this.tokenExpiresAt = tokenExpiresAt;
        this.scopes = scopes;
        this.lastValidatedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    void prePersist() {
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = timestamp;
        }
        if (updatedAt == null) {
            updatedAt = timestamp;
        }
    }

    public void replace(String encryptedAccessToken, Instant tokenExpiresAt, String scopes, Instant now) {
        this.encryptedAccessToken = encryptedAccessToken;
        this.tokenExpiresAt = tokenExpiresAt;
        this.scopes = scopes;
        this.lastValidatedAt = now;
        this.updatedAt = now;
    }

    public boolean isExpired(Instant now) {
        return tokenExpiresAt != null && !tokenExpiresAt.isAfter(now);
    }

    public UUID getId() { return id; }
    public SocialAccount getSocialAccount() { return socialAccount; }
    public String getCredentialType() { return credentialType; }
    public String getEncryptedAccessToken() { return encryptedAccessToken; }
    public Instant getTokenExpiresAt() { return tokenExpiresAt; }
    public String getScopes() { return scopes; }
    public Instant getLastValidatedAt() { return lastValidatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
