package com.fdmultimedia.api.accounts;

import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A workspace-scoped external publishing destination/account.
 *
 * This entity holds only account metadata. It deliberately has no columns
 * for provider access tokens or other secrets: a real provider's
 * credentials, when Phase 10B adds one, must live in a separate,
 * secret-managed store keyed by this entity's id, never here, never in a
 * Job payload, and never serialized back to the browser. The {@code TEST}
 * platform requires no credential at all.
 */
@Entity
@Table(name = "social_accounts")
public class SocialAccount {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SocialPlatform platform;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "external_account_id")
    private String externalAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SocialAccountStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private AppUser createdByUser;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SocialAccount() {
    }

    public SocialAccount(
            Workspace workspace,
            SocialPlatform platform,
            String displayName,
            AppUser createdByUser,
            Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.platform = platform;
        this.displayName = displayName;
        this.status = SocialAccountStatus.ACTIVE;
        this.createdByUser = createdByUser;
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

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public SocialPlatform getPlatform() { return platform; }
    public String getDisplayName() { return displayName; }
    public String getExternalAccountId() { return externalAccountId; }
    public SocialAccountStatus getStatus() { return status; }
    public AppUser getCreatedByUser() { return createdByUser; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
