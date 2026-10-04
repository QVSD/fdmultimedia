package com.fdmultimedia.api.safety;

import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.ExecutionOrigin;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Phase 17M value types. Safety monitoring is observational: nothing in this package mutates a Robot. */
public final class PostChangeSafetyModels {
    private PostChangeSafetyModels() {}

    public static final String ENGINE_VERSION = "POST_CHANGE_SAFETY_V1";
    public static final String NON_CAUSAL_DISCLAIMER =
            "Observed post-change difference is not proof that the configuration change caused the outcome.";

    public enum MonitorStatus { MONITORING, COMPLETED, SUPERSEDED }

    public enum CompletedReason { HORIZON_REACHED, EPOCH_SUPERSEDED }

    /** Declaration order is the deterministic evidence precedence used by the evaluator. */
    public enum EvaluationStatus {
        SUPERSEDED, BASELINE_UNAVAILABLE, TOO_YOUNG, METRIC_UNAVAILABLE, INSUFFICIENT_SAMPLE, LOW_COVERAGE,
        NOT_COMPARABLE, READY_STABLE, READY_REGRESSION_OBSERVED
    }

    public enum Trigger { EXPLICIT, RECONCILIATION }

    public enum AdverseDirection { LOWER_IS_ADVERSE, HIGHER_IS_ADVERSE }

    public enum RecommendationStatus { OPEN, ACKNOWLEDGED, DISMISSED, ROLLED_BACK, SUPERSEDED;
        public boolean actionable() { return this == OPEN || this == ACKNOWLEDGED; }
    }

    /** Windows evaluated for every monitor. H24 is informational and never produces a recommendation. */
    public static final List<Window> MONITORED_WINDOWS = List.of(Window.H24, Window.H72, Window.D7);

    public static boolean informational(Window window) { return window == Window.H24; }

    public static boolean decisionWindow(Window window) { return window == Window.H72 || window == Window.D7; }

    /** One RobotRun-level observation of the monitored epoch (statistical unit = RobotRun, like the Experiment analysis). */
    public record CohortRow(UUID runId, UUID publicationId, Instant publishedAt, String provider, boolean eligibleByAge,
            UUID snapshotId, BigDecimal metricValue) {}

    /** Aggregate of {@link CohortRow}s; {@code digest} fingerprints the exact selected evidence. */
    public record CohortStats(int runs, int published, int eligible, int sample, BigDecimal mean, Set<String> providers,
            String digest) {
        public BigDecimal coverage() {
            return eligible == 0 ? null : BigDecimal.valueOf(sample).divide(BigDecimal.valueOf(eligible), 6, java.math.RoundingMode.HALF_UP);
        }
    }

    public record BaselineFacts(UUID id, String provider, int sample, BigDecimal coverage, BigDecimal mean, String fingerprint) {}

    public record Thresholds(int minSample, BigDecimal minCoverage, BigDecimal materialPercent) {}

    public record Outcome(EvaluationStatus status, List<String> reasons, BigDecimal absoluteDifference,
            BigDecimal relativePercent, BigDecimal adverseMagnitudePercent, BigDecimal postCoverage) {}

    public record MonitorRecord(UUID id, UUID workspaceId, UUID robotId, UUID revisionId, int robotRevision,
            ExecutionOrigin executionOrigin, UUID authorizationId, UUID proposalId, UUID experimentId, UUID previousPersonaId,
            String previousPersonaName, UUID newPersonaId, String newPersonaName, Metric metric, Instant epochStart,
            Instant epochEnd, MonitorStatus status, CompletedReason completedReason, Instant createdAt, Instant lastEvaluatedAt) {}

    public record BaselineRecord(UUID id, UUID workspaceId, UUID monitorId, Window window, UUID experimentId,
            String analysisEngineVersion, Metric metric, String provider, int assignmentCount, int eligibleCount, int sample,
            BigDecimal eligibleCoverage, BigDecimal assignmentCoverage, BigDecimal mean, String fingerprint, Instant frozenAt) {}

    public record EvaluationRecord(UUID id, UUID workspaceId, UUID monitorId, UUID robotId, UUID revisionId,
            int evaluationRevision, String engineVersion, Window window, boolean informational, EvaluationStatus status,
            List<String> reasons, Trigger trigger, Metric metric, String provider, AdverseDirection direction,
            ExecutionOrigin executionOrigin, UUID authorizationId, UUID proposalId, UUID experimentId, UUID previousPersonaId,
            UUID newPersonaId, UUID baselineId, Integer baselineSample, BigDecimal baselineCoverage, BigDecimal baselineValue,
            Instant epochStart, Instant epochEnd, Instant cohortEnd, int postRuns, int postPublished, int postEligible,
            int postSample, BigDecimal postCoverage, BigDecimal postValue, BigDecimal absoluteDifference,
            BigDecimal relativeDifferencePercent, BigDecimal materialThresholdPercent, int minSample, BigDecimal minCoverage,
            String evidenceFingerprint, Instant evaluatedAt) {}

    public record RecommendationRecord(UUID id, UUID workspaceId, UUID robotId, UUID revisionId, int robotRevision,
            UUID monitorId, UUID evaluationId, Window window, RecommendationStatus status, Metric metric, String provider,
            UUID previousPersonaId, String previousPersonaName, UUID currentPersonaId, String currentPersonaName,
            ExecutionOrigin executionOrigin, UUID authorizationId, int baselineSample, BigDecimal baselineValue,
            int postSample, BigDecimal postCoverage, BigDecimal postValue, BigDecimal absoluteDifference,
            BigDecimal relativeDifferencePercent, String reason, String limitations, Instant createdAt, Instant acknowledgedAt,
            Instant dismissedAt, Instant resolvedAt, UUID rollbackRevisionId) {}

    // ---- API views ----

    public record EvaluateRequest(String observationWindow) {}

    public record RollbackActionRequest(String reason) {}

    public record RevisionSafety(UUID revisionId, int robotRevision, ExecutionOrigin executionOrigin, UUID authorizationId,
            UUID monitorId, MonitorStatus monitorStatus, Metric metric, String provider, Instant epochStart, Instant epochEnd,
            List<EvaluationRecord> latestEvaluations, List<BaselineRecord> baselines, RecommendationRecord recommendation,
            String disclaimer) {}
}
