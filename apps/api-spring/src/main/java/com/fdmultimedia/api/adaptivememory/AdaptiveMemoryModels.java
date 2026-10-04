package com.fdmultimedia.api.adaptivememory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 17N value types. Adaptive memory is a deterministic, rebuildable projection over existing immutable history
 * (OptimizationProposal, RobotChangeProposal, RobotConfigurationRevision, 17M evaluations/recommendations). It is not a
 * score, a ranking, a learned model or a second source of truth.
 */
public final class AdaptiveMemoryModels {
    private AdaptiveMemoryModels() {}

    public static final String ENGINE_VERSION = "ADAPTIVE_MEMORY_V1";
    public static final String SCREENING_ENGINE_VERSION = "ADAPTIVE_MEMORY_SCREENING_V1";

    /** V1 suppression durations are constants (documented), all measured from the source fact's own timestamp. */
    public static final Duration PROPOSAL_SUPPRESSION = Duration.ofDays(30);
    public static final Duration REJECTION_SUPPRESSION = Duration.ofDays(30);
    public static final Duration APPLIED_SUPPRESSION = Duration.ofDays(30);
    public static final Duration REGRESSION_SUPPRESSION = Duration.ofDays(90);
    public static final Duration ROLLBACK_SUPPRESSION = Duration.ofDays(90);

    /** Factual lifecycle outcome of a transition for one Robot. Deliberately unordered: it is never a score. */
    public enum Outcome { PROPOSED, HUMAN_REJECTED, APPROVED_NOT_APPLIED, APPLIED, OBSERVED_STABLE, OBSERVED_REGRESSION, ROLLED_BACK, SUPERSEDED }

    public enum EventType {
        PROPOSAL_CREATED, PROPOSAL_REJECTED, CHANGE_PROPOSAL_CREATED, CHANGE_PROPOSAL_APPROVED, CHANGE_APPLIED, SAFETY_STABLE,
        SAFETY_REGRESSION, ROLLBACK_RECOMMENDED, ROLLBACK_DISMISSED, CHANGE_ROLLED_BACK, TRANSITION_SUPERSEDED
    }

    public enum SourceType {
        OPTIMIZATION_PROPOSAL, ROBOT_CHANGE_PROPOSAL, ROBOT_CONFIGURATION_REVISION, POST_CHANGE_SAFETY_EVALUATION, ROLLBACK_RECOMMENDATION
    }

    /** Declaration order is the deterministic reporting order. */
    public enum SuppressionReason { RECENTLY_PROPOSED, HUMAN_REJECTED, RECENTLY_APPLIED, OBSERVED_REGRESSION, ROLLED_BACK, CURRENTLY_ACTIVE }

    /** One immutable source fact projected for a Robot transition. */
    public record Fact(EventType type, SourceType sourceType, UUID sourceId, Instant occurredAt, UUID workspaceId, UUID robotId,
            UUID fromPersonaId, UUID toPersonaId, UUID revisionId, UUID experimentId, UUID evaluationId, UUID recommendationId,
            String executionOrigin, String window, String detail) {}

    /** Directional, Robot-scoped projection row: (robot, from, to). Every field is derivable from the event history. */
    public record Memory(UUID workspaceId, UUID robotId, UUID fromPersonaId, UUID toPersonaId, Outcome latestOutcome,
            int proposalCount, int applyCount, int rollbackCount, int regressionCount, Instant firstSeenAt, Instant lastSeenAt,
            Instant lastProposedAt, Instant lastRejectedAt, Instant lastAppliedAt, Instant lastRegressionAt, Instant lastRolledBackAt,
            Instant latestEvidenceAt, UUID latestRevisionId, UUID latestEvaluationId, UUID latestRecommendationId, UUID latestExperimentId,
            String latestSafetyStatus, int eventCount) {}

    /** Screening verdict for one transition. {@code suppressionUntil} is null for a permanent-while-active or empty decision. */
    public record Decision(UUID robotId, UUID fromPersonaId, UUID toPersonaId, boolean eligible, List<SuppressionReason> reasons,
            Instant suppressionUntil, Outcome latestOutcome, Instant latestEvidenceAt, UUID latestRevisionId,
            UUID latestEvaluationId, UUID latestRecommendationId, UUID latestExperimentId, String engineVersion) {}

    /** Read model for the UI: the memory row plus the live screening decision and Persona names. */
    public record MemoryView(UUID fromPersonaId, String fromPersonaName, UUID toPersonaId, String toPersonaName, Outcome latestOutcome,
            int proposalCount, int applyCount, int rollbackCount, int regressionCount, Instant firstSeenAt, Instant lastSeenAt,
            Instant latestEvidenceAt, String latestSafetyStatus, boolean suppressed, List<SuppressionReason> reasons,
            Instant suppressionUntil, UUID latestRevisionId, UUID latestEvaluationId, UUID latestRecommendationId) {}

    /** Read-only manual-flow answer: a warning, never a block. */
    public record DecisionView(Decision decision, String fromPersonaName, String toPersonaName, boolean warning) {}

    public record RobotMemory(UUID robotId, UUID currentPersonaId, String engineVersion, List<MemoryView> transitions, int totalTransitions) {}
}
