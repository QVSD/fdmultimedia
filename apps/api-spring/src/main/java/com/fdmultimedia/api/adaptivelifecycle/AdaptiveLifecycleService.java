package com.fdmultimedia.api.adaptivelifecycle;

import com.fdmultimedia.api.adaptivelifecycle.AdaptiveLifecycleModels.*;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.optimization.*;
import com.fdmultimedia.api.optimization.AutonomousProposalModels.Evaluation;
import com.fdmultimedia.api.optimization.OptimizationProposalModels.Origin;
import com.fdmultimedia.api.robotchanges.*;
import com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.AuthorizationStatus;
import com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.ExecutionEligibility;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.PolicySummary;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.ProposalAutomationMode;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.ChangeType;
import com.fdmultimedia.api.robots.*;
import com.fdmultimedia.api.safety.PostChangeSafetyModels.*;
import com.fdmultimedia.api.safety.PostChangeSafetyService;
import com.fdmultimedia.api.workspaces.Workspace;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 17O composition root. It only reads canonical 17H-17N facts and feeds the pure precedence engine;
 * it cannot create proposals, authorize, apply, roll back, reconcile, or persist lifecycle state.
 */
@Service
public class AdaptiveLifecycleService {
    private static final int PROPOSAL_LIMIT = 20;
    private final AuthService auth;
    private final RobotRepository robots;
    private final RobotAdaptivePolicyService policies;
    private final AutonomousProposalService autonomous;
    private final OptimizationProposalRepository optimizationProposals;
    private final RobotChangeProposalRepository changeProposals;
    private final RobotAdaptiveExecutionAuthorizationRepository authorizations;
    private final AdaptiveExecutionService execution;
    private final AdaptiveGuardrailService guardrails;
    private final RobotConfigurationRevisionRepository revisions;
    private final PostChangeSafetyService safety;
    private final Clock clock;
    private final AdaptiveLifecycleEngine engine = new AdaptiveLifecycleEngine();

    public AdaptiveLifecycleService(AuthService auth, RobotRepository robots, RobotAdaptivePolicyService policies,
            AutonomousProposalService autonomous, OptimizationProposalRepository optimizationProposals,
            RobotChangeProposalRepository changeProposals,
            RobotAdaptiveExecutionAuthorizationRepository authorizations, AdaptiveExecutionService execution,
            AdaptiveGuardrailService guardrails, RobotConfigurationRevisionRepository revisions,
            PostChangeSafetyService safety, Clock clock) {
        this.auth = auth; this.robots = robots; this.policies = policies; this.autonomous = autonomous;
        this.optimizationProposals = optimizationProposals; this.changeProposals = changeProposals;
        this.authorizations = authorizations; this.execution = execution; this.guardrails = guardrails;
        this.revisions = revisions; this.safety = safety; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RobotAdaptiveLifecycle get(AuthenticatedUser user, UUID robotId) {
        Workspace workspace = auth.currentMembershipFor(user).getWorkspace();
        Robot robot = robots.findByWorkspaceAndId(workspace, robotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot not found"));
        UUID personaId = robot.getPersona() == null ? null : robot.getPersona().getId();
        PolicySummary policy = policies.get(user, robotId);
        RobotConfigurationRevision latest = revisions.findTopByRobotIdOrderByRevisionDesc(robotId).orElse(null);
        boolean revisionMatches = latest != null && Objects.equals(personaId, latest.getNewPersonaId());
        Instant epochStart = revisionMatches ? latest.getCreatedAt() : robot.getUpdatedAt();

        OptimizationProposal optimization = currentOptimization(workspace, robot, personaId, epochStart);
        RobotChangeProposal change = currentChange(workspace, robot, personaId, epochStart);
        if (change != null && optimization == null)
            optimization = optimizationProposals.findByWorkspaceAndId(workspace, change.getSourceOptimizationProposalId()).orElse(null);

        RobotAdaptiveExecutionAuthorization authorization = change == null ? null : latestAuthorization(workspace, change.getId());
        ExecutionEligibility executionEligibility = null;
        RobotAdaptivePolicyModels.EvaluationSummary guardrail = null;
        if (change != null && change.getStatus() == RobotChangeProposalModels.Status.APPROVED) {
            if (authorization != null && authorization.isActive()) executionEligibility = execution.eligibility(user, authorization.getId());
            else guardrail = guardrails.preview(user, change.getId());
        }

        RevisionSafety revisionSafety = revisionMatches && latest.getChangeType() == ChangeType.PERSONA_CHANGE
                ? safety.robotSafety(user, robotId).stream().filter(s -> s.revisionId().equals(latest.getId())).findFirst().orElse(null)
                : null;
        RecommendationRecord recommendation = revisionSafety == null ? null : revisionSafety.recommendation();
        EvaluationRecord safetyEvaluation = canonicalSafety(revisionSafety);
        boolean recommendationActionable = recommendation != null && recommendation.status().actionable();
        boolean recommendationResolvedRegression = recommendation != null
                && (recommendation.status() == RecommendationStatus.DISMISSED || recommendation.status() == RecommendationStatus.SUPERSEDED
                    || recommendation.status() == RecommendationStatus.ROLLED_BACK);

        Evaluation opportunity = autonomous.dryRun(user, robotId);
        List<String> guardrailReasons = executionEligibility != null ? executionEligibility.guardrailReasons()
                : guardrail == null ? List.of() : guardrail.reasons().stream().map(Enum::name).toList();
        String authorizationStatus = authorization == null ? null : effectiveAuthorizationStatus(authorization, Instant.now(clock));
        boolean sourceAutomated = optimization != null && optimization.getOrigin() == Origin.AUTO_PROPOSE;
        boolean currentForward = revisionMatches && latest.getChangeType() == ChangeType.PERSONA_CHANGE;

        AdaptiveLifecycleEngine.Result result = engine.derive(new AdaptiveLifecycleEngine.Input(
                policy.proposalAutomationMode() == ProposalAutomationMode.MANUAL_ONLY, policy.enabled(), currentForward,
                safetyEvaluation == null ? null : safetyEvaluation.status().name(), recommendationActionable,
                recommendationResolvedRegression, optimization == null ? null : optimization.getStatus().name(),
                change == null ? null : change.getStatus().name(), sourceAutomated, authorizationStatus,
                executionEligibility != null && executionEligibility.eligibleNow(), guardrailReasons,
                opportunity.eligible(), opportunity.reasons().stream().map(Enum::name).toList()));

        List<AdaptiveLifecycleModels.Reason> reasons = new ArrayList<>(result.reasons());
        if (latest != null && !revisionMatches && !reasons.contains(AdaptiveLifecycleModels.Reason.CONFIGURATION_SUPERSEDED))
            reasons.add(AdaptiveLifecycleModels.Reason.CONFIGURATION_SUPERSEDED);
        reasons.sort(Comparator.comparingInt(AdaptiveLifecycleModels.Reason::ordinal));
        List<MemorySummary> memory = opportunity.memorySkippedCandidates().stream()
                .map(m -> new MemorySummary(m.candidatePersonaId(), m.reasons(), m.suppressionUntil(), m.latestOutcome())).toList();

        return new RobotAdaptiveLifecycle(workspace.getId(), robotId, result.state(), AdaptiveLifecycleModels.ENGINE_VERSION,
                Instant.now(clock), revisionMatches ? latest.getId() : null, revisionMatches ? latest.getRevision() : null,
                personaId, policy.proposalAutomationMode().name(), policy.revision(),
                optimization == null ? null : optimization.getId(), optimization == null ? null : optimization.getStatus().name(),
                optimization == null ? null : optimization.getOrigin().name(), change == null ? null : change.getId(),
                change == null ? null : change.getStatus().name(), authorization == null ? null : authorization.getId(),
                authorizationStatus, guardrail == null && executionEligibility == null ? null
                        : executionEligibility != null ? executionEligibility.eligibleNow() : guardrail.eligible(),
                guardrailReasons, revisionSafety == null ? null : revisionSafety.monitorId(),
                safetyEvaluation == null ? null : safetyEvaluation.status().name(),
                recommendation == null ? null : recommendation.id(), recommendation == null ? null : recommendation.status().name(),
                memory, result.nextAction(), result.human(), result.automatic(), List.copyOf(reasons),
                evidence(optimization, change, safetyEvaluation, opportunity));
    }

    private OptimizationProposal currentOptimization(Workspace workspace, Robot robot, UUID personaId, Instant epochStart) {
        if (personaId == null) return null;
        return optimizationProposals.findCurrentForLifecycle(workspace, robot.getId(), personaId, epochStart,
                PageRequest.of(0, PROPOSAL_LIMIT)).stream().findFirst().orElse(null);
    }

    private RobotChangeProposal currentChange(Workspace workspace, Robot robot, UUID personaId, Instant epochStart) {
        String fingerprint = RobotChangeProposalService.robotConfigFingerprint(robot.getId(), personaId);
        return changeProposals.findCurrentForLifecycle(workspace, robot.getId(), fingerprint, epochStart,
                PageRequest.of(0, PROPOSAL_LIMIT)).stream().findFirst().orElse(null);
    }

    private RobotAdaptiveExecutionAuthorization latestAuthorization(Workspace workspace, UUID proposalId) {
        List<RobotAdaptiveExecutionAuthorization> rows = authorizations.findByWorkspaceAndProposalIdOrderByCreatedAtDesc(
                workspace, proposalId, PageRequest.of(0, 20));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String effectiveAuthorizationStatus(RobotAdaptiveExecutionAuthorization authorization, Instant now) {
        return authorization.isActive() && authorization.isExpiredAt(now) ? AuthorizationStatus.EXPIRED.name()
                : authorization.getStatus().name();
    }

    private static EvaluationRecord canonicalSafety(RevisionSafety safety) {
        if (safety == null || safety.latestEvaluations().isEmpty()) return null;
        Optional<EvaluationRecord> regression = safety.latestEvaluations().stream()
                .filter(e -> e.status() == EvaluationStatus.READY_REGRESSION_OBSERVED).findFirst();
        if (regression.isPresent()) return regression.get();
        Optional<EvaluationRecord> stable = safety.latestEvaluations().stream()
                .filter(e -> e.status() == EvaluationStatus.READY_STABLE).findFirst();
        return stable.orElseGet(() -> safety.latestEvaluations().stream()
                .max(Comparator.comparingInt(e -> switch (e.window()) { case D7 -> 3; case H72 -> 2; default -> 1; })).orElse(null));
    }

    private static EvidenceSummary evidence(OptimizationProposal optimization, RobotChangeProposal change,
            EvaluationRecord safety, Evaluation opportunity) {
        if (safety != null) return new EvidenceSummary(null, safety.metric().name(), safety.window().name(),
                safety.postSample(), safety.postCoverage(), safety.status().name());
        if (change != null) return new EvidenceSummary(null, change.getMetric().name(), change.getObservationWindow().name(),
                Math.min(change.getBaselineSampleCount(), change.getCandidateSampleCount()), min(change.getBaselineCoverage(), change.getCandidateCoverage()),
                change.getStatus().name());
        if (optimization != null) return new EvidenceSummary(optimization.getSourceReview().getId(), optimization.getMetric().name(),
                optimization.getObservationWindow().name(), Math.min(optimization.getBaselineSample(), optimization.getCandidateSample()),
                min(optimization.getBaselineCoverage(), optimization.getCandidateCoverage()), optimization.getStatus().name());
        return new EvidenceSummary(opportunity.sourceReviewId(), null, null, null, null,
                opportunity.eligible() ? "ELIGIBLE" : opportunity.reasons().stream().map(Enum::name).findFirst().orElse("UNAVAILABLE"));
    }

    private static BigDecimal min(BigDecimal left, BigDecimal right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.min(right);
    }
}
