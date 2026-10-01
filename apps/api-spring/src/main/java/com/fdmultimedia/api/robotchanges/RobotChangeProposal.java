package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.analytics.DashboardQuery;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.Status;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Human-approved evidence that a target Robot's PERSONA should move from one
 * value to another, derived from a completed controlled Experiment that was
 * materialized from a Phase 17H {@link com.fdmultimedia.api.optimization.OptimizationProposal}.
 * A different audit concept from that proposal (17H asks "should we test A
 * vs B"; this asks "given completed evidence, should Robot X move from A to
 * B") and from Phase 15A's {@code DecisionApplicationRecord} (which applies a
 * manually-recorded {@code ExperimentDecisionRecord}, not a 17H-originated
 * Experiment directly). Every evidentiary field here is frozen at creation
 * time from the Phase 14B analysis and never re-read live — see
 * {@code RobotChangeProposalService.create}. This row and {@link
 * RobotConfigurationRevision} are the only two types in this codebase
 * permitted to cause {@code Robot.applyPersona} to run from automated
 * evidence, and only after an explicit human {@link #approve} followed by a
 * separate, explicit Apply call.
 */
@Entity
@Table(name = "robot_change_proposals")
public class RobotChangeProposal {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id") private Workspace workspace;
    @Column(name = "source_optimization_proposal_id", nullable = false) private UUID sourceOptimizationProposalId;
    @Column(name = "source_experiment_id", nullable = false) private UUID sourceExperimentId;
    @Column(name = "engine_version", nullable = false) private String engineVersion;
    @Column(nullable = false) private String factor;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(name = "target_robot_id", nullable = false) private UUID targetRobotId;
    @Column(name = "target_robot_name_snapshot", nullable = false) private String targetRobotNameSnapshot;
    @Column(name = "current_persona_id") private UUID currentPersonaId;
    @Column(name = "current_persona_name_snapshot") private String currentPersonaNameSnapshot;
    @Column(name = "current_persona_fingerprint", nullable = false) private String currentPersonaFingerprint;
    @Column(name = "proposed_persona_id", nullable = false) private UUID proposedPersonaId;
    @Column(name = "proposed_persona_name_snapshot", nullable = false) private String proposedPersonaNameSnapshot;
    @Column(name = "proposed_persona_fingerprint", nullable = false) private String proposedPersonaFingerprint;
    @Column(name = "expected_robot_config_fingerprint", nullable = false) private String expectedRobotConfigFingerprint;
    @Column(name = "analysis_engine_version", nullable = false) private String analysisEngineVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DashboardQuery.Metric metric;
    @Enumerated(EnumType.STRING) @Column(name = "observation_window", nullable = false) private DashboardQuery.Window observationWindow;
    @Column(nullable = false) private String population;
    @Column(name = "baseline_sample_count", nullable = false) private int baselineSampleCount;
    @Column(name = "candidate_sample_count", nullable = false) private int candidateSampleCount;
    @Column(name = "baseline_coverage", precision = 7, scale = 6) private BigDecimal baselineCoverage;
    @Column(name = "candidate_coverage", precision = 7, scale = 6) private BigDecimal candidateCoverage;
    @Column(name = "absolute_mean_difference", nullable = false, precision = 30, scale = 6) private BigDecimal absoluteMeanDifference;
    @Column(name = "relative_mean_difference_percent", precision = 20, scale = 6) private BigDecimal relativeMeanDifferencePercent;
    @Column(name = "standard_error", precision = 30, scale = 6) private BigDecimal standardError;
    @Column(name = "degrees_of_freedom", precision = 20, scale = 6) private BigDecimal degreesOfFreedom;
    @Column(name = "confidence_interval_lower", precision = 30, scale = 6) private BigDecimal confidenceIntervalLower;
    @Column(name = "confidence_interval_upper", precision = 30, scale = 6) private BigDecimal confidenceIntervalUpper;
    @Column(name = "confidence_interval_includes_zero") private Boolean confidenceIntervalIncludesZero;
    @Column(name = "p_value", precision = 20, scale = 6) private BigDecimal pValue;
    @Column(name = "standardized_effect_size", precision = 20, scale = 6) private BigDecimal standardizedEffectSize;
    @Column(nullable = false) private String limitations;
    @Column(nullable = false) private String rationale;
    @Column(name = "proposal_fingerprint", nullable = false) private String proposalFingerprint;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by_user_id") private AppUser createdByUser;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "applied_at") private Instant appliedAt;
    @Column(name = "rolled_back_at") private Instant rolledBackAt;

    protected RobotChangeProposal() {}

    public RobotChangeProposal(Workspace workspace, UUID sourceOptimizationProposalId, UUID sourceExperimentId,
            UUID targetRobotId, String targetRobotNameSnapshot, UUID currentPersonaId, String currentPersonaNameSnapshot,
            String currentPersonaFingerprint, UUID proposedPersonaId, String proposedPersonaNameSnapshot,
            String proposedPersonaFingerprint, String expectedRobotConfigFingerprint, DashboardQuery.Metric metric,
            DashboardQuery.Window observationWindow, int baselineSampleCount, int candidateSampleCount,
            BigDecimal baselineCoverage, BigDecimal candidateCoverage, BigDecimal absoluteMeanDifference,
            BigDecimal relativeMeanDifferencePercent, BigDecimal standardError, BigDecimal degreesOfFreedom,
            BigDecimal confidenceIntervalLower, BigDecimal confidenceIntervalUpper, Boolean confidenceIntervalIncludesZero,
            BigDecimal pValue, BigDecimal standardizedEffectSize, String limitations, String rationale,
            String proposalFingerprint, AppUser createdByUser, Instant now) {
        this.id = UUID.randomUUID(); this.workspace = workspace;
        this.sourceOptimizationProposalId = sourceOptimizationProposalId; this.sourceExperimentId = sourceExperimentId;
        this.engineVersion = RobotChangeProposalService.ENGINE_VERSION; this.factor = "PERSONA";
        this.status = Status.READY_FOR_REVIEW; this.targetRobotId = targetRobotId;
        this.targetRobotNameSnapshot = targetRobotNameSnapshot; this.currentPersonaId = currentPersonaId;
        this.currentPersonaNameSnapshot = currentPersonaNameSnapshot; this.currentPersonaFingerprint = currentPersonaFingerprint;
        this.proposedPersonaId = proposedPersonaId; this.proposedPersonaNameSnapshot = proposedPersonaNameSnapshot;
        this.proposedPersonaFingerprint = proposedPersonaFingerprint; this.expectedRobotConfigFingerprint = expectedRobotConfigFingerprint;
        this.analysisEngineVersion = com.fdmultimedia.api.experiments.ExperimentAnalysisService.ANALYSIS_VERSION;
        this.metric = metric; this.observationWindow = observationWindow; this.population = "ASSIGNED_OBSERVED";
        this.baselineSampleCount = baselineSampleCount; this.candidateSampleCount = candidateSampleCount;
        this.baselineCoverage = baselineCoverage; this.candidateCoverage = candidateCoverage;
        this.absoluteMeanDifference = absoluteMeanDifference; this.relativeMeanDifferencePercent = relativeMeanDifferencePercent;
        this.standardError = standardError; this.degreesOfFreedom = degreesOfFreedom;
        this.confidenceIntervalLower = confidenceIntervalLower; this.confidenceIntervalUpper = confidenceIntervalUpper;
        this.confidenceIntervalIncludesZero = confidenceIntervalIncludesZero; this.pValue = pValue;
        this.standardizedEffectSize = standardizedEffectSize; this.limitations = limitations; this.rationale = rationale;
        this.proposalFingerprint = proposalFingerprint; this.createdByUser = createdByUser; this.createdAt = now;
    }

    public void approve(Instant now) { require(Status.READY_FOR_REVIEW); status = Status.APPROVED; reviewedAt = now; }
    public void reject(Instant now) { require(Status.READY_FOR_REVIEW); status = Status.REJECTED; reviewedAt = now; }
    public void markApplied(Instant now) { require(Status.APPROVED); status = Status.APPLIED; appliedAt = now; }
    public void markStale() { status = Status.STALE; }
    public void markRolledBack(Instant now) { status = Status.ROLLED_BACK; rolledBackAt = now; }
    private void require(Status expected) { if (status != expected) throw new IllegalStateException("Proposal must be " + expected); }

    public UUID getId() { return id; } public Workspace getWorkspace() { return workspace; }
    public UUID getSourceOptimizationProposalId() { return sourceOptimizationProposalId; }
    public UUID getSourceExperimentId() { return sourceExperimentId; }
    public String getEngineVersion() { return engineVersion; } public String getFactor() { return factor; }
    public Status getStatus() { return status; } public UUID getTargetRobotId() { return targetRobotId; }
    public String getTargetRobotNameSnapshot() { return targetRobotNameSnapshot; }
    public UUID getCurrentPersonaId() { return currentPersonaId; } public String getCurrentPersonaNameSnapshot() { return currentPersonaNameSnapshot; }
    public String getCurrentPersonaFingerprint() { return currentPersonaFingerprint; }
    public UUID getProposedPersonaId() { return proposedPersonaId; } public String getProposedPersonaNameSnapshot() { return proposedPersonaNameSnapshot; }
    public String getProposedPersonaFingerprint() { return proposedPersonaFingerprint; }
    public String getExpectedRobotConfigFingerprint() { return expectedRobotConfigFingerprint; }
    public String getAnalysisEngineVersion() { return analysisEngineVersion; }
    public DashboardQuery.Metric getMetric() { return metric; } public DashboardQuery.Window getObservationWindow() { return observationWindow; }
    public String getPopulation() { return population; }
    public int getBaselineSampleCount() { return baselineSampleCount; } public int getCandidateSampleCount() { return candidateSampleCount; }
    public BigDecimal getBaselineCoverage() { return baselineCoverage; } public BigDecimal getCandidateCoverage() { return candidateCoverage; }
    public BigDecimal getAbsoluteMeanDifference() { return absoluteMeanDifference; }
    public BigDecimal getRelativeMeanDifferencePercent() { return relativeMeanDifferencePercent; }
    public BigDecimal getStandardError() { return standardError; } public BigDecimal getDegreesOfFreedom() { return degreesOfFreedom; }
    public BigDecimal getConfidenceIntervalLower() { return confidenceIntervalLower; } public BigDecimal getConfidenceIntervalUpper() { return confidenceIntervalUpper; }
    public Boolean getConfidenceIntervalIncludesZero() { return confidenceIntervalIncludesZero; }
    public BigDecimal getPValue() { return pValue; } public BigDecimal getStandardizedEffectSize() { return standardizedEffectSize; }
    public String getLimitations() { return limitations; } public String getRationale() { return rationale; }
    public String getProposalFingerprint() { return proposalFingerprint; } public AppUser getCreatedByUser() { return createdByUser; }
    public Instant getCreatedAt() { return createdAt; } public Instant getReviewedAt() { return reviewedAt; }
    public Instant getAppliedAt() { return appliedAt; } public Instant getRolledBackAt() { return rolledBackAt; }
}
