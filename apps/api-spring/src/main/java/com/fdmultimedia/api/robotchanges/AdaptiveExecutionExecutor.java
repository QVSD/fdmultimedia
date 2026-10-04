package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.personas.PersonaRepository;
import com.fdmultimedia.api.personas.PersonaStatus;
import com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.*;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.Trigger;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.ExecutionOrigin;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.Status;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotRepository;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 17L pre-authorized execution orchestration ({@code PREAUTHORIZED_EXECUTION_V1}). It never creates or
 * approves proposals, never touches Experiments, never rolls back, and has no AI dependency: it can only invoke
 * the canonical 17I/17J Apply core for one already human-approved proposal covered by an ACTIVE, unexpired,
 * human-created authorization. Lock order is always authorization, then proposal, then Robot.
 */
@Component
public class AdaptiveExecutionExecutor {
    public static final String ENGINE_VERSION = "PREAUTHORIZED_EXECUTION_V1";

    private final RobotAdaptiveExecutionAuthorizationRepository authorizations;
    private final AdaptiveExecutionAttemptRepository attempts;
    private final RobotChangeProposalRepository proposals;
    private final RobotRepository robots;
    private final PersonaRepository personas;
    private final AdaptiveGuardrailService guardrails;
    private final RobotChangeProposalService applier;
    private final Clock clock;

    public AdaptiveExecutionExecutor(RobotAdaptiveExecutionAuthorizationRepository authorizations,
            AdaptiveExecutionAttemptRepository attempts, RobotChangeProposalRepository proposals, RobotRepository robots,
            PersonaRepository personas, AdaptiveGuardrailService guardrails, RobotChangeProposalService applier, Clock clock) {
        this.authorizations = authorizations; this.attempts = attempts; this.proposals = proposals; this.robots = robots;
        this.personas = personas; this.guardrails = guardrails; this.applier = applier; this.clock = clock;
    }

    record Inspection(List<Reason> reasons, List<String> guardrailReasons, boolean fingerprintMatch,
            boolean personaActive, TerminalReason permanent, boolean staleProposal) {
        boolean eligible() { return reasons.size() == 1 && reasons.get(0) == Reason.ELIGIBLE; }
    }

    /** Pure evaluation: no writes. Used by the read-only dry-run and by the locked executor transaction. */
    Inspection inspect(Workspace w, RobotAdaptiveExecutionAuthorization a, RobotChangeProposal p, Robot robot, Instant now) {
        Set<Reason> reasons = new TreeSet<>();
        List<String> guardrailReasons = List.of();
        boolean fingerprintMatch = true, personaActive = true, stale = false;
        TerminalReason permanent = null;
        switch (a.getStatus()) {
            case REVOKED -> reasons.add(Reason.AUTHORIZATION_REVOKED);
            case EXPIRED -> reasons.add(Reason.AUTHORIZATION_EXPIRED);
            case CONSUMED -> reasons.add(Reason.AUTHORIZATION_CONSUMED);
            case INVALIDATED -> reasons.add(Reason.AUTHORIZATION_INVALIDATED);
            case ACTIVE -> {}
        }
        if (!a.isActive()) return new Inspection(List.copyOf(reasons), guardrailReasons, true, true, null, false);
        if (a.isExpiredAt(now)) { reasons.add(Reason.AUTHORIZATION_EXPIRED); permanent = TerminalReason.EXPIRED; }
        switch (p.getStatus()) {
            case APPROVED -> {}
            case APPLIED -> { reasons.add(Reason.PROPOSAL_APPLIED); if (permanent == null) permanent = TerminalReason.APPLIED_MANUALLY; }
            case READY_FOR_REVIEW -> { reasons.add(Reason.PROPOSAL_NOT_APPROVED); if (permanent == null) permanent = TerminalReason.PROPOSAL_NOT_APPROVED; }
            default -> { reasons.add(Reason.PROPOSAL_STALE); if (permanent == null) permanent = TerminalReason.PROPOSAL_STALE; }
        }
        if (!a.getRobotId().equals(p.getTargetRobotId()) || !a.getToPersonaId().equals(p.getProposedPersonaId())
                || !Objects.equals(a.getFromPersonaId(), p.getCurrentPersonaId())
                || !a.getSourceExperimentId().equals(p.getSourceExperimentId())) {
            reasons.add(Reason.SCOPE_MISMATCH); if (permanent == null) permanent = TerminalReason.SCOPE_MISMATCH;
        }
        if (p.getStatus() == Status.APPROVED) {
            Persona current = robot.getPersona();
            fingerprintMatch = RobotChangeProposalService.robotConfigFingerprint(robot.getId(), current == null ? null : current.getId())
                    .equals(p.getExpectedRobotConfigFingerprint());
            if (!fingerprintMatch) {
                reasons.add(Reason.ROBOT_CONFIGURATION_CHANGED); stale = true;
                if (permanent == null) permanent = TerminalReason.ROBOT_CONFIGURATION_CHANGED;
            }
            Persona target = personas.findByWorkspaceAndId(w, a.getToPersonaId()).orElse(null);
            personaActive = target != null && target.getStatus() == PersonaStatus.ACTIVE;
            if (!personaActive) {
                reasons.add(Reason.TARGET_PERSONA_INACTIVE); stale = true;
                if (permanent == null) permanent = TerminalReason.TARGET_PERSONA_INACTIVE;
            }
            AdaptiveGuardrailEvaluation evaluation = guardrails.evaluate(w, robot, p, Trigger.CHECK);
            if (!evaluation.isEligible()) {
                guardrailReasons = Arrays.asList(evaluation.getReasonCodes().split(","));
                if (guardrailReasons.contains(RobotAdaptivePolicyModels.Reason.POLICY_DISABLED.name())) reasons.add(Reason.ADAPTIVE_POLICY_DISABLED);
                if (guardrailReasons.stream().anyMatch(c -> !c.equals(RobotAdaptivePolicyModels.Reason.POLICY_DISABLED.name())))
                    reasons.add(Reason.GUARDRAIL_BLOCKED);
            }
        }
        if (reasons.isEmpty()) reasons.add(Reason.ELIGIBLE);
        return new Inspection(List.copyOf(reasons), guardrailReasons, fingerprintMatch, personaActive, permanent, stale);
    }

    /**
     * One automatic execution attempt in its own transaction. Outcomes are mutually exclusive because the
     * authorization row lock serializes this with revoke and with the human Apply (which pre-locks the same row).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AttemptResult attempt(UUID authorizationId, AttemptTrigger trigger) {
        RobotAdaptiveExecutionAuthorization a = authorizations.findByIdForUpdate(authorizationId).orElse(null);
        if (a == null || !a.isActive()) return null;
        Workspace w = a.getWorkspace();
        Instant now = Instant.now(clock);
        RobotChangeProposal p = proposals.findByWorkspaceAndIdForUpdate(w, a.getProposalId()).orElseThrow();
        Robot robot = robots.findByWorkspaceAndIdForUpdate(w, a.getRobotId()).orElseThrow();
        Inspection in = inspect(w, a, p, robot, now);
        a.markEvaluated(now);

        if (in.permanent() != null) {
            AttemptResult result;
            switch (in.permanent()) {
                case EXPIRED -> { a.expire(now); result = AttemptResult.EXPIRED; }
                case APPLIED_MANUALLY -> { a.invalidate(in.permanent(), now); result = AttemptResult.ALREADY_APPLIED; }
                default -> { a.invalidate(in.permanent(), now); result = AttemptResult.INVALIDATED; }
            }
            if (in.staleProposal() && p.getStatus() == Status.APPROVED) { p.markStale(); proposals.saveAndFlush(p); }
            authorizations.saveAndFlush(a);
            record(a, trigger, result, in, null, null, now, true);
            return result;
        }
        if (!in.eligible()) {
            authorizations.saveAndFlush(a);
            record(a, trigger, AttemptResult.BLOCKED, in, null, null, now, false);
            return AttemptResult.BLOCKED;
        }
        AdaptiveExecutionModels.ApplyResult applied = applier.applyCore(w, a.getCreatedBy(), p.getId(),
                ExecutionOrigin.PREAUTHORIZED_AUTO_APPLY, a.getId());
        if (!applied.applied()) {
            authorizations.saveAndFlush(a);
            record(a, trigger, AttemptResult.BLOCKED, new Inspection(List.of(Reason.GUARDRAIL_BLOCKED), List.of(),
                    true, true, null, false), applied.guardrailEvaluationId(), null, now, false);
            return AttemptResult.BLOCKED;
        }
        a.consume(applied.revisionId(), applied.guardrailEvaluationId(), now);
        authorizations.saveAndFlush(a);
        record(a, trigger, AttemptResult.APPLIED, in, applied.guardrailEvaluationId(), applied.revisionId(), now, true);
        return AttemptResult.APPLIED;
    }

    private void record(RobotAdaptiveExecutionAuthorization a, AttemptTrigger trigger, AttemptResult result, Inspection in,
            UUID evaluationId, UUID revisionId, Instant now, boolean always) {
        String reasons = String.join(",", in.reasons().stream().map(Enum::name).toList());
        if (!in.guardrailReasons().isEmpty()) reasons += "|" + String.join(",", in.guardrailReasons());
        if (reasons.length() > 480) reasons = reasons.substring(0, 480);
        String fingerprint = RobotChangeProposalService.sha256(result + "|" + reasons);
        if (!always) {
            var latest = attempts.findTopByAuthorizationIdOrderByAttemptedAtDesc(a.getId());
            if (latest.isPresent() && latest.get().getStateFingerprint().equals(fingerprint)) return;
        }
        attempts.saveAndFlush(new AdaptiveExecutionAttempt(a.getWorkspace(), a.getId(), a.getProposalId(), a.getRobotId(),
                trigger, result, reasons, evaluationId, revisionId, fingerprint, now));
    }
}
