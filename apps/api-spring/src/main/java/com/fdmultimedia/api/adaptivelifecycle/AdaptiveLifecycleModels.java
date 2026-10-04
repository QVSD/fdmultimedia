package com.fdmultimedia.api.adaptivelifecycle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Phase 17O bounded, side-effect-free Robot adaptive lifecycle read model. */
public final class AdaptiveLifecycleModels {
    private AdaptiveLifecycleModels() {}

    public static final String ENGINE_VERSION = "ADAPTIVE_LIFECYCLE_V1";

    public enum State {
        MANUAL_ONLY, WAITING_FOR_EVIDENCE, OPPORTUNITY_AVAILABLE, PROPOSAL_AWAITING_REVIEW,
        PROPOSAL_APPROVED, WAITING_FOR_AUTHORIZATION, AUTHORIZED_WAITING_FOR_GUARDRAILS,
        READY_FOR_APPLY, OBSERVING, STABLE, ROLLBACK_REVIEW_RECOMMENDED, MEMORY_SUPPRESSED, BLOCKED
    }

    public enum NextAction {
        NONE, COLLECT_EVIDENCE, REVIEW_PROPOSAL, MATERIALIZE_EXPERIMENT, AUTHORIZE_EXECUTION,
        WAIT_FOR_GUARDRAILS, APPLY_CHANGE, WAIT_FOR_OBSERVATION, REVIEW_ROLLBACK,
        RECONSIDER_AFTER_SUPPRESSION
    }

    /** Declaration order is the stable response order. */
    public enum Reason {
        ROLLBACK_RECOMMENDATION_OPEN, SAFETY_OBSERVATION_IN_PROGRESS, SAFETY_STABLE,
        SAFETY_REGRESSION_REVIEW_RESOLVED, CHANGE_RECENTLY_APPLIED, PROPOSAL_PENDING_REVIEW,
        PROPOSAL_APPROVED, AUTHORIZATION_REQUIRED, AUTHORIZATION_ACTIVE, AUTHORIZATION_EXPIRED,
        AUTHORIZATION_REVOKED, AUTHORIZATION_INVALIDATED, GUARDRAILS_BLOCKED, POLICY_DISABLED,
        CHANGE_BUDGET_EXHAUSTED, COOLDOWN_ACTIVE, ACTIVE_EXPERIMENT, PENDING_CHANGE_EXISTS,
        POST_CHANGE_OBSERVATION_REQUIRED, MEMORY_SUPPRESSION_ACTIVE, INSUFFICIENT_EVIDENCE,
        NO_ELIGIBLE_CANDIDATE, DUPLICATE_OPPORTUNITY, POLICY_MANUAL_ONLY, CONFIGURATION_SUPERSEDED
    }

    public record EvidenceSummary(UUID sourceReviewId, String metric, String window, Integer sample,
            BigDecimal coverage, String status) {}

    public record MemorySummary(UUID candidatePersonaId, List<String> reasons, Instant suppressionUntil,
            String latestOutcome) {}

    public record RobotAdaptiveLifecycle(UUID workspaceId, UUID robotId, State state, String engineVersion,
            Instant computedAt, UUID configurationRevisionId, Integer configurationRevisionNumber,
            UUID currentPersonaId, String policyMode, Integer policyRevision,
            UUID optimizationProposalId, String optimizationProposalStatus, String proposalOrigin,
            UUID robotChangeProposalId, String robotChangeProposalStatus,
            UUID authorizationId, String authorizationStatus, Boolean guardrailEligible,
            List<String> guardrailReasons, UUID monitorId, String safetyStatus,
            UUID recommendationId, String recommendationStatus, List<MemorySummary> memory,
            NextAction nextAction, boolean humanActionRequired, boolean automaticActionPossible,
            List<Reason> reasonCodes, EvidenceSummary evidenceSummary) {}
}
