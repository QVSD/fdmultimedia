package com.fdmultimedia.api.adaptivelifecycle;

import static com.fdmultimedia.api.adaptivelifecycle.AdaptiveLifecycleModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdaptiveLifecycleEngineTest {
    private final AdaptiveLifecycleEngine engine = new AdaptiveLifecycleEngine();

    @Test void manualOnlyIsTheQuietBaseline() { assertState(input(), State.MANUAL_ONLY, NextAction.NONE, false, false); }
    @Test void autoModeWithoutEvidenceWaits() { assertState(input(false, true), State.WAITING_FOR_EVIDENCE, NextAction.COLLECT_EVIDENCE, false, false); }
    @Test void eligiblePreviewExposesOpportunityWithoutHumanApprovalClaim() {
        var in = input(false, true, null, null, false, null, null, false, null, false, List.of(), true, List.of());
        assertState(in, State.OPPORTUNITY_AVAILABLE, NextAction.REVIEW_PROPOSAL, false, true);
    }
    @Test void memorySuppressionOutranksOrdinaryEvidenceWait() {
        var in = input(false, true, null, null, false, null, null, false, null, false, List.of(), false,
                List.of("ALL_CANDIDATES_MEMORY_SUPPRESSED"));
        assertState(in, State.MEMORY_SUPPRESSED, NextAction.RECONSIDER_AFTER_SUPPRESSION, false, false);
    }
    @Test void pendingOptimizationProposalOutranksManualPolicy() {
        assertState(input(true, true, null, null, false, "READY_FOR_REVIEW", null, false, null, false, List.of(), false, List.of("MANUAL_ONLY")),
                State.PROPOSAL_AWAITING_REVIEW, NextAction.REVIEW_PROPOSAL, true, false);
    }
    @Test void approvedOptimizationProposalNeedsHumanMaterialization() {
        assertState(input(true, true, null, null, false, "APPROVED", null, false, null, false, List.of(), false, List.of()),
                State.PROPOSAL_APPROVED, NextAction.MATERIALIZE_EXPERIMENT, true, false);
    }
    @Test void materializedOptimizationWaitsForControlledExperimentEvidence() {
        assertState(input(false, true, null, null, false, "MATERIALIZED", null, false, null, false, List.of(), false, List.of()),
                State.WAITING_FOR_EVIDENCE, NextAction.COLLECT_EVIDENCE, false, false);
    }
    @Test void pendingRobotChangeOutranksOptimizationProposal() {
        assertState(input(false, true, null, null, false, "APPROVED", "READY_FOR_REVIEW", false, null, false, List.of(), false, List.of()),
                State.PROPOSAL_AWAITING_REVIEW, NextAction.REVIEW_PROPOSAL, true, false);
    }
    @Test void manualApprovedChangeIsReadyForHumanApply() {
        assertState(input(false, true, null, null, false, null, "APPROVED", false, null, false, List.of(), false, List.of()),
                State.READY_FOR_APPLY, NextAction.APPLY_CHANGE, true, false);
    }
    @Test void autonomousApprovedChangeWaitsForHumanAuthorization() {
        assertState(input(false, true, null, null, false, null, "APPROVED", true, null, false, List.of(), false, List.of()),
                State.WAITING_FOR_AUTHORIZATION, NextAction.AUTHORIZE_EXECUTION, true, false);
    }
    @Test void expiredAuthorizationIsFreshlyReflected() {
        var result = engine.derive(input(false, true, null, null, false, null, "APPROVED", true, "EXPIRED", false, List.of(), false, List.of()));
        assertThat(result.state()).isEqualTo(State.WAITING_FOR_AUTHORIZATION);
        assertThat(result.reasons()).containsExactly(Reason.PROPOSAL_APPROVED, Reason.AUTHORIZATION_REQUIRED, Reason.AUTHORIZATION_EXPIRED);
    }
    @Test void revokedAuthorizationIsFreshlyReflected() {
        var result = engine.derive(input(false, true, null, null, false, null, "APPROVED", true, "REVOKED", false, List.of(), false, List.of()));
        assertThat(result.reasons()).contains(Reason.AUTHORIZATION_REVOKED);
    }
    @Test void activeAuthorizationBlockedByBudgetWaitsForGuardrails() {
        var result = engine.derive(input(false, true, null, null, false, null, "APPROVED", true, "ACTIVE", false,
                List.of("CHANGE_BUDGET_EXHAUSTED"), false, List.of()));
        assertThat(result.state()).isEqualTo(State.AUTHORIZED_WAITING_FOR_GUARDRAILS);
        assertThat(result.reasons()).contains(Reason.GUARDRAILS_BLOCKED, Reason.CHANGE_BUDGET_EXHAUSTED);
    }
    @Test void activeEligibleAuthorizationAllowsExistingExecutorOnly() {
        assertState(input(false, true, null, null, false, null, "APPROVED", true, "ACTIVE", true, List.of(), false, List.of()),
                State.READY_FOR_APPLY, NextAction.APPLY_CHANGE, false, true);
    }
    @Test void currentForwardRevisionObservesBeforeAnyNewProposal() {
        assertState(input(false, true, "TOO_YOUNG", null, false, "READY_FOR_REVIEW", "READY_FOR_REVIEW", false, null, false,
                List.of(), true, List.of()), State.OBSERVING, NextAction.WAIT_FOR_OBSERVATION, false, false);
    }
    @Test void matureStableSafetyIsStable() {
        assertState(input(false, true, "READY_STABLE", null, false, null, null, false, null, false, List.of(), false, List.of()),
                State.STABLE, NextAction.NONE, false, false);
    }
    @Test void actionableRollbackRecommendationHasHighestPrecedence() {
        assertState(input(false, true, "READY_STABLE", null, true, "READY_FOR_REVIEW", "APPROVED", true, "ACTIVE", true,
                List.of(), true, List.of()), State.ROLLBACK_REVIEW_RECOMMENDED, NextAction.REVIEW_ROLLBACK, true, false);
    }
    @Test void dismissedRegressionDoesNotPretendToBeStable() {
        var result = engine.derive(input(false, true, "READY_REGRESSION_OBSERVED", null, false, null, null, false, null, false,
                List.of(), false, List.of(), true));
        assertThat(result.state()).isEqualTo(State.BLOCKED);
        assertThat(result.reasons()).containsExactly(Reason.SAFETY_REGRESSION_REVIEW_RESOLVED);
    }
    @Test void disabledAdaptivePolicyIsExplicitlyBlocked() {
        var result = engine.derive(input(false, false, null, null, false, null, null, false, null, false, List.of(), false,
                List.of("ADAPTIVE_POLICY_DISABLED")));
        assertThat(result.state()).isEqualTo(State.BLOCKED);
        assertThat(result.reasons()).contains(Reason.POLICY_DISABLED);
    }
    @Test void reasonOrderingIsStableEnumOrdering() {
        var result = engine.derive(input(false, true, null, null, false, null, "APPROVED", true, "ACTIVE", false,
                List.of("POST_CHANGE_OBSERVATION_REQUIRED", "COOLDOWN_ACTIVE", "CHANGE_BUDGET_EXHAUSTED"), false, List.of()));
        assertThat(result.reasons()).containsExactly(Reason.PROPOSAL_APPROVED, Reason.AUTHORIZATION_ACTIVE,
                Reason.GUARDRAILS_BLOCKED, Reason.CHANGE_BUDGET_EXHAUSTED, Reason.COOLDOWN_ACTIVE,
                Reason.POST_CHANGE_OBSERVATION_REQUIRED);
    }

    private void assertState(AdaptiveLifecycleEngine.Input input, State state, NextAction next, boolean human, boolean automatic) {
        var result = engine.derive(input);
        assertThat(result.state()).isEqualTo(state);
        assertThat(result.nextAction()).isEqualTo(next);
        assertThat(result.human()).isEqualTo(human);
        assertThat(result.automatic()).isEqualTo(automatic);
    }

    private AdaptiveLifecycleEngine.Input input() { return input(true, true); }
    private AdaptiveLifecycleEngine.Input input(boolean manual, boolean enabled) {
        return input(manual, enabled, null, null, false, null, null, false, null, false, List.of(), false, List.of());
    }
    private AdaptiveLifecycleEngine.Input input(boolean manual, boolean enabled, String safety, String ignored,
            boolean recommendation, String optimization, String change, boolean auto, String authorization,
            boolean authorizationEligible, List<String> guardrails, boolean opportunity, List<String> opportunityReasons) {
        return input(manual, enabled, safety, ignored, recommendation, optimization, change, auto, authorization,
                authorizationEligible, guardrails, opportunity, opportunityReasons, false);
    }
    private AdaptiveLifecycleEngine.Input input(boolean manual, boolean enabled, String safety, String ignored,
            boolean recommendation, String optimization, String change, boolean auto, String authorization,
            boolean authorizationEligible, List<String> guardrails, boolean opportunity, List<String> opportunityReasons,
            boolean resolvedRegression) {
        return new AdaptiveLifecycleEngine.Input(manual, enabled, safety != null, safety, recommendation, resolvedRegression,
                optimization, change, auto, authorization, authorizationEligible, guardrails, opportunity, opportunityReasons);
    }
}
