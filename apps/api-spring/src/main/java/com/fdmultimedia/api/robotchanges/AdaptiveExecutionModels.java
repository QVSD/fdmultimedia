package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.Status;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Phase 17L: pre-authorized, single-use, expiring execution of one exact human-approved PERSONA change. */
public final class AdaptiveExecutionModels {
    private AdaptiveExecutionModels() {}

    public enum AuthorizationStatus { ACTIVE, CONSUMED, REVOKED, EXPIRED, INVALIDATED }

    public enum TerminalReason {
        AUTO_APPLIED, REVOKED_BY_HUMAN, EXPIRED, APPLIED_MANUALLY, PROPOSAL_STALE, PROPOSAL_NOT_APPROVED,
        ROBOT_CONFIGURATION_CHANGED, TARGET_PERSONA_INACTIVE, SCOPE_MISMATCH
    }

    public enum AttemptTrigger { AUTHORIZATION_CREATED, RECONCILIATION, MANUAL_APPLY, AUTHORIZATION_REVOKED }

    public enum AttemptResult { APPLIED, BLOCKED, INVALIDATED, EXPIRED, REVOKED, ALREADY_APPLIED }

    /** Declaration order is the stable reporting order. */
    public enum Reason {
        AUTHORIZATION_REVOKED, AUTHORIZATION_EXPIRED, AUTHORIZATION_CONSUMED, AUTHORIZATION_INVALIDATED,
        PROPOSAL_NOT_APPROVED, PROPOSAL_APPLIED, PROPOSAL_STALE, SCOPE_MISMATCH, ROBOT_CONFIGURATION_CHANGED,
        TARGET_PERSONA_INACTIVE, ADAPTIVE_POLICY_DISABLED, GUARDRAIL_BLOCKED, ELIGIBLE
    }

    public record CreateRequest(Integer durationHours) {}

    public record AuthorizationSummary(UUID id, UUID proposalId, UUID robotId, String robotName, String factor,
            UUID fromPersonaId, String fromPersonaName, UUID toPersonaId, String toPersonaName, UUID sourceExperimentId,
            int maxExecutions, AuthorizationStatus status, TerminalReason terminalReason, Instant validFrom,
            Instant expiresAt, int policyRevision, String executionEngineVersion, UUID createdByUserId, Instant createdAt,
            Instant terminatedAt, UUID consumedRevisionId, UUID consumedGuardrailEvaluationId) {}

    public record ExecutionEligibility(UUID authorizationId, AuthorizationStatus status, Status proposalStatus,
            Instant expiresAt, boolean eligibleNow, List<Reason> reasons, List<String> guardrailReasons,
            boolean robotFingerprintMatch, boolean targetPersonaActive, Instant evaluatedAt) {}

    public record AttemptSummary(UUID id, UUID authorizationId, AttemptTrigger trigger, AttemptResult result,
            List<String> reasons, UUID guardrailEvaluationId, UUID revisionId, Instant attemptedAt) {}

    public record ApplyResult(RobotChangeProposalModels.Summary summary, boolean applied, UUID revisionId,
            UUID guardrailEvaluationId) {}
}
