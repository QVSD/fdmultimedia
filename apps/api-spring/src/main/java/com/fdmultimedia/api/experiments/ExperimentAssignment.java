package com.fdmultimedia.api.experiments;

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
 * The durable, immutable record of one RobotRun's controlled assignment —
 * created exactly once, by {@link ExperimentAssignmentService#assign}, in
 * the same transaction as the RobotRun itself, always before the
 * experimental factor is ever consumed. Never updated afterward: pause,
 * resume, Robot edits, and Persona edits/archival all leave an existing
 * assignment untouched (items 21/26/27/31/32).
 */
@Entity
@Table(name = "experiment_assignments")
public class ExperimentAssignment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experiment_id", nullable = false)
    private Experiment experiment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experiment_variant_id", nullable = false)
    private ExperimentVariant experimentVariant;

    @Column(name = "robot_run_id", nullable = false)
    private UUID robotRunId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_strategy", nullable = false)
    private AssignmentStrategy assignmentStrategy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExperimentFactor factor;

    @Column(name = "factor_value_id")
    private UUID factorValueId;

    @Column(name = "factor_value_name_snapshot", nullable = false)
    private String factorValueNameSnapshot;

    protected ExperimentAssignment() {
    }

    public ExperimentAssignment(Workspace workspace, Experiment experiment, ExperimentVariant variant, UUID robotRunId, Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.experiment = experiment;
        this.experimentVariant = variant;
        this.robotRunId = robotRunId;
        this.assignedAt = now;
        this.assignmentStrategy = experiment.getAssignmentStrategy();
        this.factor = experiment.getFactor();
        this.factorValueId = variant.getPersonaId();
        this.factorValueNameSnapshot = variant.getPersonaNameSnapshot();
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (assignedAt == null) {
            assignedAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public Experiment getExperiment() { return experiment; }
    public ExperimentVariant getExperimentVariant() { return experimentVariant; }
    public UUID getRobotRunId() { return robotRunId; }
    public Instant getAssignedAt() { return assignedAt; }
    public AssignmentStrategy getAssignmentStrategy() { return assignmentStrategy; }
    public ExperimentFactor getFactor() { return factor; }
    public UUID getFactorValueId() { return factorValueId; }
    public String getFactorValueNameSnapshot() { return factorValueNameSnapshot; }
}
