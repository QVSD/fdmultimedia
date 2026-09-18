package com.fdmultimedia.api.publishschedules;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.contentdrafts.ContentDraft;
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
 * "Publish this ContentDraft to this SocialAccount at this instant." Not a
 * Job, a Publication, a ContentDraft, or a Robot. Destination
 * ({@code socialAccount}), content ({@code mediaAsset}), and
 * {@code captionSnapshot} are fixed at creation time and never re-read from
 * the live Draft — editing the Draft afterward must not silently change what
 * an already-scheduled post will publish.
 */
@Entity
@Table(name = "publish_schedules")
public class PublishSchedule {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_draft_id", nullable = false)
    private ContentDraft contentDraft;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_asset_id", nullable = false)
    private MediaAsset mediaAsset;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "social_account_id", nullable = false)
    private SocialAccount socialAccount;

    @Column(name = "caption_snapshot")
    private String captionSnapshot;

    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PublishScheduleStatus status;

    @Column(name = "publication_id")
    private UUID publicationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private AppUser createdByUser;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    protected PublishSchedule() {
    }

    public PublishSchedule(
            Workspace workspace,
            ContentDraft contentDraft,
            MediaAsset mediaAsset,
            SocialAccount socialAccount,
            String captionSnapshot,
            Instant scheduledFor,
            AppUser createdByUser,
            Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.contentDraft = contentDraft;
        this.mediaAsset = mediaAsset;
        this.socialAccount = socialAccount;
        this.captionSnapshot = captionSnapshot;
        this.scheduledFor = scheduledFor;
        this.status = PublishScheduleStatus.SCHEDULED;
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

    public void markDispatched(UUID publicationId, Instant now) {
        if (status != PublishScheduleStatus.SCHEDULED) {
            throw new IllegalStateException("Only a scheduled item can be dispatched");
        }
        this.status = PublishScheduleStatus.DISPATCHED;
        this.publicationId = publicationId;
        this.dispatchedAt = now;
        this.updatedAt = now;
    }

    public void markFailed(String failureCode, String failureMessage, Instant now) {
        if (status != PublishScheduleStatus.SCHEDULED) {
            throw new IllegalStateException("Only a scheduled item can fail dispatch");
        }
        this.status = PublishScheduleStatus.FAILED;
        this.failureCode = normalize(failureCode);
        this.failureMessage = normalize(failureMessage);
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        if (status != PublishScheduleStatus.SCHEDULED) {
            throw new IllegalStateException("Only a scheduled item can be cancelled");
        }
        this.status = PublishScheduleStatus.CANCELLED;
        this.cancelledAt = now;
        this.updatedAt = now;
    }

    public void reschedule(Instant newScheduledFor, Instant now) {
        if (status != PublishScheduleStatus.SCHEDULED) {
            throw new IllegalStateException("Only a scheduled item can be rescheduled");
        }
        this.scheduledFor = newScheduledFor;
        this.updatedAt = now;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "Scheduling failed" : value.trim();
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public ContentDraft getContentDraft() { return contentDraft; }
    public MediaAsset getMediaAsset() { return mediaAsset; }
    public SocialAccount getSocialAccount() { return socialAccount; }
    public String getCaptionSnapshot() { return captionSnapshot; }
    public Instant getScheduledFor() { return scheduledFor; }
    public PublishScheduleStatus getStatus() { return status; }
    public UUID getPublicationId() { return publicationId; }
    public AppUser getCreatedByUser() { return createdByUser; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
}
