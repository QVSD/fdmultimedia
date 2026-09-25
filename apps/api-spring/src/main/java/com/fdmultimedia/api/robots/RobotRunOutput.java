package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.highlights.HighlightCandidate;
import com.fdmultimedia.api.highlights.HighlightSelection;
import com.fdmultimedia.api.highlights.HighlightSelectionItem;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "robot_run_outputs")
public class RobotRunOutput {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id")
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "robot_run_id")
    private RobotRun robotRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "highlight_selection_id")
    private HighlightSelection highlightSelection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "highlight_selection_item_id")
    private HighlightSelectionItem selectionItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "highlight_candidate_id")
    private HighlightCandidate candidate;

    @Column(name = "selection_order", nullable = false)
    private int selectionOrder;

    @Column(name = "source_rank", nullable = false)
    private int sourceRank;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RobotRunOutputStatus status;

    @Column(name = "content_draft_id")
    private UUID contentDraftId;

    @Column(name = "content_suggestion_id")
    private UUID contentSuggestionId;

    @Column(name = "robot_approval_id")
    private UUID robotApprovalId;

    @Column(name = "publish_schedule_id")
    private UUID publishScheduleId;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected RobotRunOutput() {
    }

    public RobotRunOutput(RobotRun run, HighlightSelection selection, HighlightSelectionItem item, Instant now) {
        id = UUID.randomUUID();
        workspace = run.getWorkspace();
        robotRun = run;
        highlightSelection = selection;
        selectionItem = item;
        candidate = item.getCandidate();
        selectionOrder = item.getSelectionOrder();
        sourceRank = item.getSourceRank();
        status = RobotRunOutputStatus.CREATED;
        createdAt = now;
        updatedAt = now;
    }

    public void waitingForDraft(UUID draftId, Instant now) {
        contentDraftId = draftId;
        status = RobotRunOutputStatus.WAITING_FOR_DRAFT;
        updatedAt = now;
    }

    public void waitingForAi(UUID suggestionId, Instant now) {
        contentSuggestionId = suggestionId;
        status = RobotRunOutputStatus.WAITING_FOR_AI;
        updatedAt = now;
    }

    public void waitingForAiReview(Instant now) {
        status = RobotRunOutputStatus.WAITING_FOR_AI_REVIEW;
        updatedAt = now;
    }

    public void waitingForReview(UUID approvalId, Instant now) {
        robotApprovalId = approvalId;
        status = RobotRunOutputStatus.WAITING_FOR_REVIEW;
        updatedAt = now;
    }

    public void scheduled(UUID scheduleId, Instant now) {
        publishScheduleId = scheduleId;
        status = RobotRunOutputStatus.SCHEDULED;
        updatedAt = now;
    }

    public void succeeded(Instant now) {
        status = RobotRunOutputStatus.SUCCEEDED;
        updatedAt = now;
        completedAt = now;
    }

    public void failed(String code, String message, Instant now) {
        status = RobotRunOutputStatus.FAILED;
        failureCode = code;
        failureMessage = bounded(message);
        updatedAt = now;
        completedAt = now;
    }

    public void cancelled(Instant now) {
        if (!status.terminal()) {
            status = RobotRunOutputStatus.CANCELLED;
            updatedAt = now;
            completedAt = now;
        }
    }

    private String bounded(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(500, trimmed.length()));
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public RobotRun getRobotRun() { return robotRun; }
    public HighlightSelection getHighlightSelection() { return highlightSelection; }
    public HighlightSelectionItem getSelectionItem() { return selectionItem; }
    public HighlightCandidate getCandidate() { return candidate; }
    public int getSelectionOrder() { return selectionOrder; }
    public int getSourceRank() { return sourceRank; }
    public RobotRunOutputStatus getStatus() { return status; }
    public UUID getContentDraftId() { return contentDraftId; }
    public UUID getContentSuggestionId() { return contentSuggestionId; }
    public UUID getRobotApprovalId() { return robotApprovalId; }
    public UUID getPublishScheduleId() { return publishScheduleId; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
