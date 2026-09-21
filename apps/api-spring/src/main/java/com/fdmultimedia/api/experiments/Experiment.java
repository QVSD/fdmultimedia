package com.fdmultimedia.api.experiments;

import com.fdmultimedia.api.analytics.DashboardQuery;
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
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A controlled assignment mechanism, not an optimizer (Phase 14A). An
 * Experiment never decides which variant is "better," never runs
 * significance testing, and never mutates a Robot/Persona/schedule on its
 * own — see docs/ARCHITECTURE.md. Its semantic definition (factor, variants,
 * target observation window, primary metric, hypothesis) is mutable only
 * while {@link ExperimentStatus#DRAFT} and freezes permanently the instant
 * it is {@link #activate activated}.
 */
@Entity
@Table(name = "experiments")
public class Experiment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(nullable = false)
    private String hypothesis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExperimentFactor factor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExperimentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_strategy", nullable = false)
    private AssignmentStrategy assignmentStrategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_observation_window", nullable = false)
    private DashboardQuery.Window targetObservationWindow;

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_metric", nullable = false)
    private DashboardQuery.Metric primaryMetric;

    @Column(name = "minimum_practical_effect", precision = 20, scale = 4)
    private BigDecimal minimumPracticalEffect;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private AppUser createdByUser;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    protected Experiment() {
    }

    public Experiment(
            Workspace workspace,
            String name,
            String description,
            String hypothesis,
            ExperimentFactor factor,
            DashboardQuery.Window targetObservationWindow,
            DashboardQuery.Metric primaryMetric,
            AppUser createdByUser,
            Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.name = name;
        this.description = description;
        this.hypothesis = hypothesis;
        this.factor = factor;
        this.status = ExperimentStatus.DRAFT;
        this.assignmentStrategy = AssignmentStrategy.DETERMINISTIC_BALANCED_V1;
        this.targetObservationWindow = targetObservationWindow;
        this.primaryMetric = primaryMetric;
        this.minimumPracticalEffect = BigDecimal.ONE;
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

    /** DRAFT-only (item 36). The factor itself is fixed at creation — never editable, even in DRAFT. */
    public void updateDraft(String name, String description, String hypothesis,
            DashboardQuery.Window targetObservationWindow, DashboardQuery.Metric primaryMetric, Instant now) {
        requireDraft();
        this.name = name;
        this.description = description;
        this.hypothesis = hypothesis;
        this.targetObservationWindow = targetObservationWindow;
        this.primaryMetric = primaryMetric;
        this.updatedAt = now;
    }

    public void setDraftPracticalEffect(BigDecimal threshold, Instant now) {
        requireDraft();
        this.minimumPracticalEffect = threshold;
        this.updatedAt = now;
    }

    public void activate(Instant now) {
        if (status != ExperimentStatus.DRAFT) {
            throw new IllegalStateException("Only a DRAFT experiment can be activated");
        }
        this.status = ExperimentStatus.ACTIVE;
        this.activatedAt = now;
        this.updatedAt = now;
    }

    public void pause(Instant now) {
        if (status != ExperimentStatus.ACTIVE) {
            throw new IllegalStateException("Only an ACTIVE experiment can be paused");
        }
        this.status = ExperimentStatus.PAUSED;
        this.updatedAt = now;
    }

    public void resume(Instant now) {
        if (status != ExperimentStatus.PAUSED) {
            throw new IllegalStateException("Only a PAUSED experiment can be resumed");
        }
        this.status = ExperimentStatus.ACTIVE;
        this.updatedAt = now;
    }

    public void complete(Instant now) {
        if (status != ExperimentStatus.ACTIVE && status != ExperimentStatus.PAUSED) {
            throw new IllegalStateException("Only an ACTIVE or PAUSED experiment can be completed");
        }
        this.status = ExperimentStatus.COMPLETED;
        this.stoppedAt = now;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        if (isTerminal()) {
            throw new IllegalStateException("A terminal experiment cannot be cancelled");
        }
        this.status = ExperimentStatus.CANCELLED;
        this.stoppedAt = now;
        this.updatedAt = now;
    }

    public boolean isTerminal() {
        return status == ExperimentStatus.COMPLETED || status == ExperimentStatus.CANCELLED;
    }

    private void requireDraft() {
        if (status != ExperimentStatus.DRAFT) {
            throw new IllegalStateException("Only a DRAFT experiment can be edited");
        }
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getHypothesis() { return hypothesis; }
    public ExperimentFactor getFactor() { return factor; }
    public ExperimentStatus getStatus() { return status; }
    public AssignmentStrategy getAssignmentStrategy() { return assignmentStrategy; }
    public DashboardQuery.Window getTargetObservationWindow() { return targetObservationWindow; }
    public DashboardQuery.Metric getPrimaryMetric() { return primaryMetric; }
    public BigDecimal getMinimumPracticalEffect() { return minimumPracticalEffect; }
    public AppUser getCreatedByUser() { return createdByUser; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getStoppedAt() { return stoppedAt; }
}
