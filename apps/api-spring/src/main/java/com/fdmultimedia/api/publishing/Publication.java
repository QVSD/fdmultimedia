package com.fdmultimedia.api.publishing;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.jobs.Job;
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
 * Represents a user's intent to publish one {@link MediaAsset} to one
 * {@link SocialAccount}, and the durable outcome of that intent. Survives
 * independently of any single Job attempt; retries reuse the same row and
 * the same underlying {@link Job} (the Job itself is requeued on retryable
 * failure, per the existing distributed job infrastructure).
 */
@Entity
@Table(name = "publications")
public class Publication {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private MediaAsset asset;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "social_account_id", nullable = false)
    private SocialAccount socialAccount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PublicationStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id")
    private Job job;

    @Column
    private String caption;

    @Column(name = "provider_request_id")
    private String providerRequestId;

    @Column(name = "provider_publication_id")
    private String providerPublicationId;

    @Column(name = "content_draft_id")
    private UUID contentDraftId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private AppUser createdByUser;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    protected Publication() {
    }

    public Publication(
            Workspace workspace,
            MediaAsset asset,
            SocialAccount socialAccount,
            String caption,
            AppUser createdByUser,
            Instant now) {
        this(workspace, asset, socialAccount, caption, createdByUser, null, now);
    }

    public Publication(
            Workspace workspace,
            MediaAsset asset,
            SocialAccount socialAccount,
            String caption,
            AppUser createdByUser,
            UUID contentDraftId,
            Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.asset = asset;
        this.socialAccount = socialAccount;
        this.status = PublicationStatus.PENDING;
        this.caption = caption;
        this.createdByUser = createdByUser;
        this.contentDraftId = contentDraftId;
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

    public void attachJob(Job job, Instant now) {
        this.job = job;
        this.updatedAt = now;
    }

    public void markPublishing(Instant now) {
        if (status != PublicationStatus.PENDING && status != PublicationStatus.PUBLISHING) {
            throw new IllegalStateException("Only pending publications can start publishing");
        }
        this.status = PublicationStatus.PUBLISHING;
        this.failureCode = null;
        this.failureMessage = null;
        this.updatedAt = now;
    }

    public void markPublished(String providerRequestId, String providerPublicationId, Instant publishedAt, Instant now) {
        if (status != PublicationStatus.PUBLISHING && status != PublicationStatus.PUBLISHED) {
            throw new IllegalStateException("Only publishing publications can complete");
        }
        this.status = PublicationStatus.PUBLISHED;
        this.providerRequestId = providerRequestId;
        this.providerPublicationId = providerPublicationId;
        this.publishedAt = publishedAt;
        this.failureCode = null;
        this.failureMessage = null;
        this.updatedAt = now;
    }

    public void markPendingForRetry(Instant now) {
        if (status != PublicationStatus.PUBLISHING && status != PublicationStatus.PENDING) {
            throw new IllegalStateException("Only active publications can be retried");
        }
        this.status = PublicationStatus.PENDING;
        this.updatedAt = now;
    }

    public void markFailed(String failureCode, String failureMessage, Instant now) {
        if (status == PublicationStatus.PUBLISHED) {
            throw new IllegalStateException("Published publications cannot fail");
        }
        this.status = PublicationStatus.FAILED;
        this.failureCode = normalize(failureCode);
        this.failureMessage = normalize(failureMessage);
        this.updatedAt = now;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "Publishing failed" : value.trim();
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public MediaAsset getAsset() { return asset; }
    public SocialAccount getSocialAccount() { return socialAccount; }
    public PublicationStatus getStatus() { return status; }
    public Job getJob() { return job; }
    public String getCaption() { return caption; }
    public String getProviderRequestId() { return providerRequestId; }
    public String getProviderPublicationId() { return providerPublicationId; }
    public UUID getContentDraftId() { return contentDraftId; }
    public AppUser getCreatedByUser() { return createdByUser; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
}
