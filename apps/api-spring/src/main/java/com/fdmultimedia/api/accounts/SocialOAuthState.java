package com.fdmultimedia.api.accounts;

import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A short-lived, single-use OAuth CSRF/state token. Only a hash of the raw
 * state value is stored; the raw value lives solely in the URL handed to the
 * browser. The callback resolves workspace/user context from this row —
 * never from callback query parameters — which is what prevents a forged or
 * replayed callback from acting on an arbitrary workspace.
 */
@Entity
@Table(name = "social_oauth_states")
public class SocialOAuthState {

    @Id
    private UUID id;

    @Column(name = "state_hash", nullable = false, unique = true)
    private String stateHash;

    @Column(nullable = false)
    private String platform;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SocialOAuthState() {
    }

    public SocialOAuthState(String stateHash, String platform, Workspace workspace, AppUser user, Instant expiresAt, Instant now) {
        this.id = UUID.randomUUID();
        this.stateHash = stateHash;
        this.platform = platform;
        this.workspace = workspace;
        this.user = user;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isUsable(String platform, Instant now) {
        return consumedAt == null && expiresAt.isAfter(now) && this.platform.equals(platform);
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }

    public String getStateHash() { return stateHash; }
    public String getPlatform() { return platform; }
    public Workspace getWorkspace() { return workspace; }
    public AppUser getUser() { return user; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
}
