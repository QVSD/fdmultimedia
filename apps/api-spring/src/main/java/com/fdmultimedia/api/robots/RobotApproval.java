package com.fdmultimedia.api.robots;

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
 * A durable proposal created by a REVIEW_REQUIRED RobotRun once its Draft is
 * READY. No real {@code Publication} or {@code PublishSchedule} exists until
 * a human approves it — approving simply calls the existing
 * {@code PublishScheduleService} exactly like a human-initiated schedule
 * would.
 */
@Entity
@Table(name = "robot_approvals")
public class RobotApproval {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "robot_run_id", nullable = false)
    private RobotRun robotRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "robot_run_output_id")
    private RobotRunOutput robotRunOutput;

    @Column(name = "content_draft_id", nullable = false)
    private UUID contentDraftId;

    @Column(name = "social_account_id", nullable = false)
    private UUID socialAccountId;

    @Column(name = "proposed_scheduled_for")
    private Instant proposedScheduledFor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RobotApprovalStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    protected RobotApproval() {
    }

    public RobotApproval(
            Workspace workspace, RobotRun robotRun, UUID contentDraftId, UUID socialAccountId,
            Instant proposedScheduledFor, Instant now) {
        this(workspace, robotRun, null, contentDraftId, socialAccountId, proposedScheduledFor, now);
    }

    public RobotApproval(
            Workspace workspace, RobotRun robotRun, RobotRunOutput robotRunOutput,
            UUID contentDraftId, UUID socialAccountId, Instant proposedScheduledFor, Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.robotRun = robotRun;
        this.robotRunOutput = robotRunOutput;
        this.contentDraftId = contentDraftId;
        this.socialAccountId = socialAccountId;
        this.proposedScheduledFor = proposedScheduledFor;
        this.status = RobotApprovalStatus.PENDING;
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

    public void approve(UUID decidedByUserId, Instant now) {
        if (status != RobotApprovalStatus.PENDING) {
            throw new IllegalStateException("Only a pending approval can be approved");
        }
        this.status = RobotApprovalStatus.APPROVED;
        this.decidedByUserId = decidedByUserId;
        this.decidedAt = now;
    }

    public void reject(UUID decidedByUserId, Instant now) {
        if (status != RobotApprovalStatus.PENDING) {
            throw new IllegalStateException("Only a pending approval can be rejected");
        }
        this.status = RobotApprovalStatus.REJECTED;
        this.decidedByUserId = decidedByUserId;
        this.decidedAt = now;
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public RobotRun getRobotRun() { return robotRun; }
    public RobotRunOutput getRobotRunOutput() { return robotRunOutput; }
    public UUID getContentDraftId() { return contentDraftId; }
    public UUID getSocialAccountId() { return socialAccountId; }
    public Instant getProposedScheduledFor() { return proposedScheduledFor; }
    public RobotApprovalStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public UUID getDecidedByUserId() { return decidedByUserId; }
}
