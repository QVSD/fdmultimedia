package com.fdmultimedia.api.contentdrafts;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.highlights.HighlightCandidate;
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

@Entity
@Table(name = "content_drafts")
public class ContentDraft {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_asset_id", nullable = false)
    private MediaAsset sourceAsset;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_asset_id", nullable = false)
    private MediaAsset mediaAsset;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_highlight_candidate_id")
    private HighlightCandidate sourceHighlightCandidate;

    @Column
    private String title;

    @Column
    private String caption;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentDraftStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_stage", nullable = false)
    private ContentDraftWorkflowStage workflowStage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pending_job_id")
    private Job pendingJob;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private AppUser createdByUser;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected ContentDraft() {
    }

    private ContentDraft(
            Workspace workspace,
            MediaAsset sourceAsset,
            MediaAsset mediaAsset,
            HighlightCandidate sourceHighlightCandidate,
            String title,
            String caption,
            AppUser createdByUser,
            Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.sourceAsset = sourceAsset;
        this.mediaAsset = mediaAsset;
        this.sourceHighlightCandidate = sourceHighlightCandidate;
        this.title = title;
        this.caption = caption;
        this.createdByUser = createdByUser;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Path A: an already READY+INSPECTED+video asset becomes a draft directly. */
    public static ContentDraft fromExistingAsset(
            Workspace workspace, MediaAsset asset, String title, String caption, AppUser createdByUser, Instant now) {
        ContentDraft draft = new ContentDraft(workspace, asset, asset, null, title, caption, createdByUser, now);
        draft.status = ContentDraftStatus.READY;
        draft.workflowStage = ContentDraftWorkflowStage.READY;
        return draft;
    }

    /** Path B: a highlight candidate starts a clip derivation immediately. */
    public static ContentDraft fromHighlightCandidate(
            Workspace workspace, HighlightCandidate candidate, MediaAsset clipInProgress, Job clipJob, AppUser createdByUser, Instant now) {
        ContentDraft draft = new ContentDraft(
                workspace, candidate.getAsset(), clipInProgress, candidate, null, null, createdByUser, now);
        draft.status = ContentDraftStatus.DRAFT;
        draft.workflowStage = ContentDraftWorkflowStage.CLIP_PENDING;
        draft.pendingJob = clipJob;
        return draft;
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

    public void advanceToVerticalPending(MediaAsset verticalInProgress, Job verticalJob, Instant now) {
        if (workflowStage != ContentDraftWorkflowStage.CLIP_PENDING) {
            throw new IllegalStateException("Only a clip-pending draft can advance to vertical preparation");
        }
        this.mediaAsset = verticalInProgress;
        this.pendingJob = verticalJob;
        this.workflowStage = ContentDraftWorkflowStage.VERTICAL_PENDING;
        this.updatedAt = now;
    }

    public void markPreparationReady(Instant now) {
        if (workflowStage != ContentDraftWorkflowStage.VERTICAL_PENDING) {
            throw new IllegalStateException("Only a vertical-pending draft can become ready");
        }
        this.workflowStage = ContentDraftWorkflowStage.READY;
        this.pendingJob = null;
        if (status == ContentDraftStatus.DRAFT) {
            this.status = ContentDraftStatus.READY;
        }
        this.updatedAt = now;
    }

    public void markPreparationFailed(String errorCode, String errorMessage, Instant now) {
        this.status = ContentDraftStatus.FAILED;
        this.failureCode = normalize(errorCode);
        this.failureMessage = normalize(errorMessage);
        this.updatedAt = now;
    }

    public void retryPreparationAsClip(MediaAsset clipInProgress, Job clipJob, Instant now) {
        if (status != ContentDraftStatus.FAILED) {
            throw new IllegalStateException("Only a failed draft can retry preparation");
        }
        this.mediaAsset = clipInProgress;
        this.pendingJob = clipJob;
        this.workflowStage = ContentDraftWorkflowStage.CLIP_PENDING;
        this.status = ContentDraftStatus.DRAFT;
        this.failureCode = null;
        this.failureMessage = null;
        this.updatedAt = now;
    }

    public void retryPreparationAsVertical(MediaAsset verticalInProgress, Job verticalJob, Instant now) {
        if (status != ContentDraftStatus.FAILED) {
            throw new IllegalStateException("Only a failed draft can retry preparation");
        }
        this.mediaAsset = verticalInProgress;
        this.pendingJob = verticalJob;
        this.workflowStage = ContentDraftWorkflowStage.VERTICAL_PENDING;
        this.status = ContentDraftStatus.DRAFT;
        this.failureCode = null;
        this.failureMessage = null;
        this.updatedAt = now;
    }

    public void updateEditableFields(String title, String caption, Instant now) {
        this.title = title;
        this.caption = caption;
        this.updatedAt = now;
    }

    public void applyPublishingDisplayState(ContentDraftStatus derived, Instant publishedAt, Instant now) {
        if (this.status == derived && (publishedAt == null || publishedAt.equals(this.publishedAt))) {
            return;
        }
        this.status = derived;
        if (publishedAt != null) {
            this.publishedAt = publishedAt;
        }
        this.updatedAt = now;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "Preparation failed" : value.trim();
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public MediaAsset getSourceAsset() { return sourceAsset; }
    public MediaAsset getMediaAsset() { return mediaAsset; }
    public HighlightCandidate getSourceHighlightCandidate() { return sourceHighlightCandidate; }
    public String getTitle() { return title; }
    public String getCaption() { return caption; }
    public ContentDraftStatus getStatus() { return status; }
    public ContentDraftWorkflowStage getWorkflowStage() { return workflowStage; }
    public Job getPendingJob() { return pendingJob; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public AppUser getCreatedByUser() { return createdByUser; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
