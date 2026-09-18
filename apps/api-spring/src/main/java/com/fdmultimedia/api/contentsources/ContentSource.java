package com.fdmultimedia.api.contentsources;

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
 * A controlled, workspace-scoped pool of MediaAssets a Robot may select
 * from — organizational, not compute infrastructure. See package-info for
 * the boundary this package deliberately does not cross.
 */
@Entity
@Table(name = "content_sources")
public class ContentSource {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentSourceType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentSourceStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private AppUser createdByUser;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContentSource() {
    }

    public ContentSource(Workspace workspace, String name, String description, AppUser createdByUser, Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.name = name;
        this.description = description;
        this.type = ContentSourceType.MEDIA_LIBRARY;
        this.status = ContentSourceStatus.ACTIVE;
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

    public void update(String name, String description, Instant now) {
        this.name = name;
        this.description = description;
        this.updatedAt = now;
    }

    public void pause(Instant now) {
        this.status = ContentSourceStatus.PAUSED;
        this.updatedAt = now;
    }

    public void resume(Instant now) {
        this.status = ContentSourceStatus.ACTIVE;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public ContentSourceType getType() { return type; }
    public ContentSourceStatus getStatus() { return status; }
    public AppUser getCreatedByUser() { return createdByUser; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
