package com.fdmultimedia.api.campaigns;

import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.robots.CampaignPlanningPolicy;
import com.fdmultimedia.api.robots.RobotRun;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One immutable-once-terminal revision of a cross-output content plan for a
 * single multi-output {@link RobotRun}. Advisory only — see package-info:
 * this row never itself creates a Draft, Publication, or schedule, and never
 * changes {@code RobotRunOutput} membership, order, or the immutable
 * {@code HighlightSelection} it coordinates around. Regeneration creates a
 * new revision (see {@link #isCurrent()}); an existing revision is never
 * mutated after it leaves {@link CampaignPlanStatus#GENERATING}.
 */
@Entity
@Table(name = "campaign_content_plans")
public class CampaignContentPlan {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "robot_run_id", nullable = false)
    private RobotRun robotRun;

    @Column(nullable = false)
    private int revision;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampaignPlanningPolicy policy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampaignPlanStatus status;

    @Column(name = "planner_version", nullable = false)
    private String plannerVersion;

    @Column
    private String provider;

    @Column
    private String model;

    @Column(name = "prompt_version")
    private String promptVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generation_job_id")
    private Job generationJob;

    @Column(name = "campaign_title")
    private String campaignTitle;

    @Column(name = "campaign_angle")
    private String campaignAngle;

    @Column(name = "input_fingerprint", nullable = false)
    private String inputFingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> configSnapshot;

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

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "applied_by_user_id")
    private UUID appliedByUserId;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejected_by_user_id")
    private UUID rejectedByUserId;

    protected CampaignContentPlan() {
    }

    public CampaignContentPlan(RobotRun robotRun, int revision, CampaignPlanningPolicy policy, String plannerVersion,
            String inputFingerprint, Map<String, Object> configSnapshot, AppUser createdByUser, Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = robotRun.getWorkspace();
        this.robotRun = robotRun;
        this.revision = revision;
        this.current = true;
        this.policy = policy;
        this.status = CampaignPlanStatus.GENERATING;
        this.plannerVersion = plannerVersion;
        this.inputFingerprint = inputFingerprint;
        this.configSnapshot = configSnapshot == null ? null : new LinkedHashMap<>(configSnapshot);
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

    public void attachGenerationJob(Job job, String provider, String model, String promptVersion, Instant now) {
        this.generationJob = job;
        this.provider = provider;
        this.model = model;
        this.promptVersion = promptVersion;
        this.updatedAt = now;
    }

    /** Deterministic planner path: generates and marks READY_FOR_REVIEW in one step — the controller decides whether to auto-apply. */
    public void markReadyForReview(String campaignTitle, String campaignAngle, Instant now) {
        if (status != CampaignPlanStatus.GENERATING) {
            throw new IllegalStateException("Plan is not generating");
        }
        this.campaignTitle = campaignTitle;
        this.campaignAngle = campaignAngle;
        this.status = CampaignPlanStatus.READY_FOR_REVIEW;
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void markFailed(String code, String message, Instant now) {
        if (status.terminal()) {
            throw new IllegalStateException("Plan is already terminal");
        }
        this.status = CampaignPlanStatus.FAILED;
        this.failureCode = bounded(code, 100);
        this.failureMessage = bounded(message, 500);
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void markApplied(UUID userId, Instant now) {
        if (status != CampaignPlanStatus.READY_FOR_REVIEW) {
            throw new IllegalStateException("Only a plan ready for review can be applied");
        }
        this.status = CampaignPlanStatus.APPLIED;
        this.appliedByUserId = userId;
        this.appliedAt = now;
        this.updatedAt = now;
    }

    public void markRejected(UUID userId, Instant now) {
        if (status != CampaignPlanStatus.READY_FOR_REVIEW) {
            throw new IllegalStateException("Only a plan ready for review can be rejected");
        }
        this.status = CampaignPlanStatus.REJECTED;
        this.rejectedByUserId = userId;
        this.rejectedAt = now;
        this.updatedAt = now;
    }

    /** Called only on the plan being superseded by a newer revision — this row's own fields never change otherwise. */
    public void supersede(Instant now) {
        this.current = false;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public RobotRun getRobotRun() { return robotRun; }
    public int getRevision() { return revision; }
    public boolean isCurrent() { return current; }
    public CampaignPlanningPolicy getPolicy() { return policy; }
    public CampaignPlanStatus getStatus() { return status; }
    public String getPlannerVersion() { return plannerVersion; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public Job getGenerationJob() { return generationJob; }
    public String getCampaignTitle() { return campaignTitle; }
    public String getCampaignAngle() { return campaignAngle; }
    public String getInputFingerprint() { return inputFingerprint; }
    public Map<String, Object> getConfigSnapshot() { return configSnapshot == null ? null : java.util.Collections.unmodifiableMap(configSnapshot); }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public AppUser getCreatedByUser() { return createdByUser; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getAppliedAt() { return appliedAt; }
    public UUID getAppliedByUserId() { return appliedByUserId; }
    public Instant getRejectedAt() { return rejectedAt; }
    public UUID getRejectedByUserId() { return rejectedByUserId; }

    private String bounded(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
