package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.analytics.DashboardQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RobotChangeProposalModels {
    private RobotChangeProposalModels() {}

    public enum Status { READY_FOR_REVIEW, APPROVED, APPLIED, REJECTED, STALE, ROLLED_BACK, FAILED }
    public enum ChangeType { PERSONA_CHANGE, ROLLBACK }

    public record CreateRequest(UUID sourceOptimizationProposalId, UUID targetRobotId) {}
    public record RollbackRequest(String reason) {}

    public record Summary(UUID id, UUID sourceOptimizationProposalId, UUID sourceExperimentId, String engineVersion,
            String factor, Status status, UUID targetRobotId, String targetRobotNameSnapshot,
            UUID currentPersonaId, String currentPersonaNameSnapshot, UUID proposedPersonaId, String proposedPersonaNameSnapshot,
            String expectedRobotConfigFingerprint, String analysisEngineVersion, DashboardQuery.Metric metric,
            DashboardQuery.Window observationWindow, String population, int baselineSampleCount, int candidateSampleCount,
            BigDecimal baselineCoverage, BigDecimal candidateCoverage, BigDecimal absoluteMeanDifference,
            BigDecimal relativeMeanDifferencePercent, BigDecimal standardError, BigDecimal degreesOfFreedom,
            BigDecimal confidenceIntervalLower, BigDecimal confidenceIntervalUpper, Boolean confidenceIntervalIncludesZero,
            BigDecimal pValue, BigDecimal standardizedEffectSize, List<String> limitations, String rationale,
            String proposalFingerprint, Instant createdAt, Instant reviewedAt, Instant appliedAt, Instant rolledBackAt) {}

    public record Eligibility(boolean eligible, String reasonCode, UUID sourceOptimizationProposalId, UUID targetRobotId) {}

    public record RevisionSummary(UUID id, UUID robotId, int revision, ChangeType changeType,
            UUID previousPersonaId, String previousPersonaNameSnapshot, UUID newPersonaId, String newPersonaNameSnapshot,
            String previousConfigFingerprint, String newConfigFingerprint, UUID sourceProposalId, UUID sourceExperimentId,
            UUID rollbackOfRevisionId, String reason, Instant createdAt, UUID guardrailEvaluationId,
            Integer adaptivePolicyRevision, String guardrailEngineVersion) {}
}
