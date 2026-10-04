package com.fdmultimedia.api.adaptivelifecycle;

import com.fdmultimedia.api.adaptivelifecycle.AdaptiveLifecycleModels.*;
import java.util.*;

/** Pure deterministic precedence engine. It has no clock, repository, provider, analytics or mutation dependency. */
final class AdaptiveLifecycleEngine {
    record Input(boolean manualOnly, boolean policyEnabled, boolean currentForwardRevision,
            String safetyStatus, boolean recommendationActionable, boolean recommendationResolvedRegression,
            String optimizationStatus, String robotChangeStatus, boolean robotChangeFromAutomation,
            String authorizationStatus, boolean authorizationEligible, List<String> guardrailReasons,
            boolean opportunity, List<String> opportunityReasons) {
        Input {
            guardrailReasons = guardrailReasons == null ? List.of() : List.copyOf(guardrailReasons);
            opportunityReasons = opportunityReasons == null ? List.of() : List.copyOf(opportunityReasons);
        }
    }

    record Result(State state, NextAction nextAction, boolean human, boolean automatic, List<Reason> reasons) {}

    Result derive(Input in) {
        EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
        if (in.recommendationActionable()) {
            reasons.add(Reason.ROLLBACK_RECOMMENDATION_OPEN);
            return result(State.ROLLBACK_REVIEW_RECOMMENDED, NextAction.REVIEW_ROLLBACK, true, false, reasons);
        }
        if (in.currentForwardRevision()) {
            if ("READY_STABLE".equals(in.safetyStatus())) {
                reasons.add(Reason.SAFETY_STABLE);
                return result(State.STABLE, NextAction.NONE, false, false, reasons);
            }
            if ("READY_REGRESSION_OBSERVED".equals(in.safetyStatus()) && in.recommendationResolvedRegression()) {
                reasons.add(Reason.SAFETY_REGRESSION_REVIEW_RESOLVED);
                return result(State.BLOCKED, NextAction.NONE, false, false, reasons);
            }
            reasons.add(Reason.SAFETY_OBSERVATION_IN_PROGRESS);
            reasons.add(Reason.CHANGE_RECENTLY_APPLIED);
            return result(State.OBSERVING, NextAction.WAIT_FOR_OBSERVATION, false, false, reasons);
        }
        if ("READY_FOR_REVIEW".equals(in.robotChangeStatus())) {
            reasons.add(Reason.PROPOSAL_PENDING_REVIEW);
            return result(State.PROPOSAL_AWAITING_REVIEW, NextAction.REVIEW_PROPOSAL, true, false, reasons);
        }
        if ("APPROVED".equals(in.robotChangeStatus())) {
            reasons.add(Reason.PROPOSAL_APPROVED);
            if ("ACTIVE".equals(in.authorizationStatus())) {
                reasons.add(Reason.AUTHORIZATION_ACTIVE);
                if (in.authorizationEligible())
                    return result(State.READY_FOR_APPLY, NextAction.APPLY_CHANGE, false, true, reasons);
                addGuardrails(reasons, in.guardrailReasons());
                return result(State.AUTHORIZED_WAITING_FOR_GUARDRAILS, NextAction.WAIT_FOR_GUARDRAILS, false, false, reasons);
            }
            addAuthorizationReason(reasons, in.authorizationStatus());
            if (in.robotChangeFromAutomation()) {
                reasons.add(Reason.AUTHORIZATION_REQUIRED);
                return result(State.WAITING_FOR_AUTHORIZATION, NextAction.AUTHORIZE_EXECUTION, true, false, reasons);
            }
            return result(State.READY_FOR_APPLY, NextAction.APPLY_CHANGE, true, false, reasons);
        }
        if ("READY_FOR_REVIEW".equals(in.optimizationStatus())) {
            reasons.add(Reason.PROPOSAL_PENDING_REVIEW);
            return result(State.PROPOSAL_AWAITING_REVIEW, NextAction.REVIEW_PROPOSAL, true, false, reasons);
        }
        if ("APPROVED".equals(in.optimizationStatus())) {
            reasons.add(Reason.PROPOSAL_APPROVED);
            return result(State.PROPOSAL_APPROVED, NextAction.MATERIALIZE_EXPERIMENT, true, false, reasons);
        }
        if ("MATERIALIZED".equals(in.optimizationStatus())) {
            reasons.add(Reason.ACTIVE_EXPERIMENT);
            return result(State.WAITING_FOR_EVIDENCE, NextAction.COLLECT_EVIDENCE, false, false, reasons);
        }
        if (in.opportunityReasons().contains("ALL_CANDIDATES_MEMORY_SUPPRESSED")) {
            reasons.add(Reason.MEMORY_SUPPRESSION_ACTIVE);
            return result(State.MEMORY_SUPPRESSED, NextAction.RECONSIDER_AFTER_SUPPRESSION, false, false, reasons);
        }
        if (in.opportunity()) return result(State.OPPORTUNITY_AVAILABLE, NextAction.REVIEW_PROPOSAL, false, true, reasons);
        addOpportunityReasons(reasons, in.opportunityReasons());
        if (in.manualOnly()) {
            reasons.add(Reason.POLICY_MANUAL_ONLY);
            return result(State.MANUAL_ONLY, NextAction.NONE, false, false, reasons);
        }
        if (!in.policyEnabled()) {
            reasons.add(Reason.POLICY_DISABLED);
            return result(State.BLOCKED, NextAction.NONE, false, false, reasons);
        }
        if (reasons.isEmpty()) reasons.add(Reason.INSUFFICIENT_EVIDENCE);
        return result(State.WAITING_FOR_EVIDENCE, NextAction.COLLECT_EVIDENCE, false, false, reasons);
    }

    private static void addAuthorizationReason(EnumSet<Reason> reasons, String status) {
        if ("EXPIRED".equals(status)) reasons.add(Reason.AUTHORIZATION_EXPIRED);
        else if ("REVOKED".equals(status)) reasons.add(Reason.AUTHORIZATION_REVOKED);
        else if ("INVALIDATED".equals(status)) reasons.add(Reason.AUTHORIZATION_INVALIDATED);
    }

    private static void addGuardrails(EnumSet<Reason> reasons, List<String> codes) {
        reasons.add(Reason.GUARDRAILS_BLOCKED);
        for (String code : codes) try { reasons.add(Reason.valueOf(code)); } catch (IllegalArgumentException ignored) {}
    }

    private static void addOpportunityReasons(EnumSet<Reason> reasons, List<String> codes) {
        for (String code : codes) {
            switch (code) {
                case "NO_ELIGIBLE_REVIEW", "STALE_EVIDENCE" -> reasons.add(Reason.INSUFFICIENT_EVIDENCE);
                case "NO_ELIGIBLE_CANDIDATE" -> reasons.add(Reason.NO_ELIGIBLE_CANDIDATE);
                case "ACTIVE_EXPERIMENT" -> reasons.add(Reason.ACTIVE_EXPERIMENT);
                case "POST_CHANGE_OBSERVATION_REQUIRED" -> reasons.add(Reason.POST_CHANGE_OBSERVATION_REQUIRED);
                case "DUPLICATE_OPPORTUNITY" -> reasons.add(Reason.DUPLICATE_OPPORTUNITY);
                case "ADAPTIVE_POLICY_DISABLED" -> reasons.add(Reason.POLICY_DISABLED);
                default -> { }
            }
        }
    }

    private static Result result(State state, NextAction next, boolean human, boolean automatic, EnumSet<Reason> reasons) {
        return new Result(state, next, human, automatic, List.copyOf(reasons));
    }
}
