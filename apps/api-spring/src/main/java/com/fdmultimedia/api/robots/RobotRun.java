package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.assets.MediaAsset;
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
 * The durable audit record of one Robot execution — not a Job, not a
 * Publication, not a ContentDraft. Progress within {@link RobotRunStatus#RUNNING}
 * is tracked by which provenance field below is populated, not by additional
 * statuses: {@code highlightAnalysisId} (waiting on analysis) ->
 * {@code highlightCandidateId} (candidate chosen) -> {@code contentDraftId}
 * (draft created). This is reconciled the same way whether triggered by a
 * read, a background poll, or right after creation — never held open on an
 * in-memory thread.
 */
@Entity
@Table(name = "robot_runs")
public class RobotRun {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "robot_id", nullable = false)
    private Robot robot;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private RobotRunTriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RobotRunStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_asset_id", nullable = false)
    private MediaAsset sourceAsset;

    @Column(name = "highlight_analysis_id")
    private UUID highlightAnalysisId;

    @Column(name = "highlight_candidate_id")
    private UUID highlightCandidateId;

    @Column(name = "content_draft_id")
    private UUID contentDraftId;

    @Column(name = "publish_schedule_id")
    private UUID publishScheduleId;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RobotRun() {
    }

    public RobotRun(Workspace workspace, Robot robot, RobotRunTriggerType triggerType, MediaAsset sourceAsset, Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.robot = robot;
        this.triggerType = triggerType;
        this.status = RobotRunStatus.RUNNING;
        this.sourceAsset = sourceAsset;
        this.startedAt = now;
        this.createdAt = now;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = startedAt != null ? startedAt : Instant.now();
        }
    }

    public boolean isTerminal() {
        return status == RobotRunStatus.SUCCEEDED || status == RobotRunStatus.FAILED || status == RobotRunStatus.CANCELLED;
    }

    public void setHighlightAnalysisId(UUID highlightAnalysisId) {
        this.highlightAnalysisId = highlightAnalysisId;
    }

    public void setHighlightCandidateId(UUID highlightCandidateId) {
        this.highlightCandidateId = highlightCandidateId;
    }

    public void markWaitingForDraft(UUID contentDraftId, Instant now) {
        this.contentDraftId = contentDraftId;
        this.status = RobotRunStatus.WAITING_FOR_DRAFT;
    }

    public void markWaitingForReview(Instant now) {
        this.status = RobotRunStatus.WAITING_FOR_REVIEW;
    }

    public void setPublishScheduleId(UUID publishScheduleId) {
        this.publishScheduleId = publishScheduleId;
    }

    public void markSucceeded(Instant now) {
        this.status = RobotRunStatus.SUCCEEDED;
        this.finishedAt = now;
    }

    public void markFailed(String failureCode, String failureMessage, Instant now) {
        this.status = RobotRunStatus.FAILED;
        this.failureCode = failureCode;
        this.failureMessage = normalize(failureMessage);
        this.finishedAt = now;
    }

    public void markCancelled(Instant now) {
        this.status = RobotRunStatus.CANCELLED;
        this.finishedAt = now;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value.trim();
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public Robot getRobot() { return robot; }
    public RobotRunTriggerType getTriggerType() { return triggerType; }
    public RobotRunStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public MediaAsset getSourceAsset() { return sourceAsset; }
    public UUID getHighlightAnalysisId() { return highlightAnalysisId; }
    public UUID getHighlightCandidateId() { return highlightCandidateId; }
    public UUID getContentDraftId() { return contentDraftId; }
    public UUID getPublishScheduleId() { return publishScheduleId; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getCreatedAt() { return createdAt; }
}
