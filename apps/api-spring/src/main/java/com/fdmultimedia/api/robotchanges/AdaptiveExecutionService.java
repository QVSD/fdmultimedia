package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.*;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.Status;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotRepository;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Human-facing authorization lifecycle: create (for one exact APPROVED proposal), read, dry-run, revoke. */
@Service
public class AdaptiveExecutionService {
    public static final int MIN_DURATION_HOURS = 1;
    public static final int MAX_DURATION_HOURS = 720;
    public static final int DEFAULT_DURATION_HOURS = 24;
    private static final int MAX_LIST = 20;

    private final AuthService auth;
    private final RobotAdaptiveExecutionAuthorizationRepository authorizations;
    private final AdaptiveExecutionAttemptRepository attempts;
    private final RobotChangeProposalRepository proposals;
    private final RobotRepository robots;
    private final RobotAdaptivePolicyService policies;
    private final AdaptiveExecutionExecutor executor;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AdaptiveExecutionService(AuthService auth, RobotAdaptiveExecutionAuthorizationRepository authorizations,
            AdaptiveExecutionAttemptRepository attempts, RobotChangeProposalRepository proposals, RobotRepository robots,
            RobotAdaptivePolicyService policies, AdaptiveExecutionExecutor executor, ApplicationEventPublisher events, Clock clock) {
        this.auth = auth; this.authorizations = authorizations; this.attempts = attempts; this.proposals = proposals;
        this.robots = robots; this.policies = policies; this.executor = executor; this.events = events; this.clock = clock;
    }

    /** Scope (Robot, from/to Persona, Experiment) is derived server-side from the proposal; the client only chooses a bounded duration. */
    @Transactional
    public AuthorizationSummary create(AuthenticatedUser principal, UUID proposalId, CreateRequest request) {
        var membership = auth.currentMembershipFor(principal);
        Workspace w = membership.getWorkspace();
        int hours = request == null || request.durationHours() == null ? DEFAULT_DURATION_HOURS : request.durationHours();
        if (hours < MIN_DURATION_HOURS || hours > MAX_DURATION_HOURS)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DURATION_HOURS_OUT_OF_BOUNDS");
        RobotChangeProposal p = proposals.findByWorkspaceAndIdForUpdate(w, proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot change proposal not found"));
        if (p.getStatus() != Status.APPROVED) throw conflict("PROPOSAL_NOT_APPROVED");
        Robot robot = robots.findByWorkspaceAndId(w, p.getTargetRobotId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot not found"));
        var persona = robot.getPersona();
        if (!RobotChangeProposalService.robotConfigFingerprint(robot.getId(), persona == null ? null : persona.getId())
                .equals(p.getExpectedRobotConfigFingerprint())) throw conflict("ROBOT_CONFIGURATION_CHANGED");
        if (authorizations.countActiveForProposal(p.getId()) > 0) throw conflict("AUTHORIZATION_ALREADY_ACTIVE");
        Instant now = Instant.now(clock);
        RobotAdaptiveExecutionAuthorization a = new RobotAdaptiveExecutionAuthorization(w, p.getId(), p.getTargetRobotId(),
                p.getCurrentPersonaId(), p.getProposedPersonaId(), p.getSourceExperimentId(), now,
                now.plus(Duration.ofHours(hours)), policies.effective(w, robot.getId()).revision(), membership.getUser(), now);
        try {
            authorizations.saveAndFlush(a);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("AUTHORIZATION_ALREADY_ACTIVE");
        }
        events.publishEvent(new AdaptiveExecutionAuthorizationCreatedEvent(a.getId()));
        return summary(a, p);
    }

    @Transactional(readOnly = true)
    public List<AuthorizationSummary> list(AuthenticatedUser principal, UUID proposalId) {
        Workspace w = auth.currentMembershipFor(principal).getWorkspace();
        RobotChangeProposal p = proposals.findByWorkspaceAndId(w, proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot change proposal not found"));
        return authorizations.findByWorkspaceAndProposalIdOrderByCreatedAtDesc(w, proposalId, PageRequest.of(0, MAX_LIST))
                .stream().map(a -> summary(a, p)).toList();
    }

    /** Read-only: evaluates the same inspection the executor uses, persists nothing. */
    @Transactional(readOnly = true)
    public ExecutionEligibility eligibility(AuthenticatedUser principal, UUID authorizationId) {
        Workspace w = auth.currentMembershipFor(principal).getWorkspace();
        RobotAdaptiveExecutionAuthorization a = authorizations.findByWorkspaceAndId(w, authorizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorization not found"));
        RobotChangeProposal p = proposals.findByWorkspaceAndId(w, a.getProposalId()).orElseThrow();
        Robot robot = robots.findByWorkspaceAndId(w, a.getRobotId()).orElseThrow();
        Instant now = Instant.now(clock);
        AdaptiveExecutionExecutor.Inspection in = executor.inspect(w, a, p, robot, now);
        return new ExecutionEligibility(a.getId(), a.getStatus(), p.getStatus(), a.getExpiresAt(), in.eligible() && a.isActive(),
                in.reasons(), in.guardrailReasons(), in.fingerprintMatch(), in.personaActive(), now);
    }

    @Transactional(readOnly = true)
    public List<AttemptSummary> attempts(AuthenticatedUser principal, UUID authorizationId) {
        Workspace w = auth.currentMembershipFor(principal).getWorkspace();
        authorizations.findByWorkspaceAndId(w, authorizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorization not found"));
        return attempts.findByAuthorizationIdOrderByAttemptedAtDesc(authorizationId, PageRequest.of(0, MAX_LIST)).stream()
                .map(x -> new AttemptSummary(x.getId(), x.getAuthorizationId(), x.getTrigger(), x.getResult(),
                        List.of(x.getReasonCodes().split("[,|]")), x.getGuardrailEvaluationId(), x.getRevisionId(), x.getAttemptedAt()))
                .toList();
    }

    /** The authorization row lock makes revoke and automatic execution mutually exclusive: exactly one terminal outcome. */
    @Transactional
    public AuthorizationSummary revoke(AuthenticatedUser principal, UUID authorizationId) {
        var membership = auth.currentMembershipFor(principal);
        Workspace w = membership.getWorkspace();
        RobotAdaptiveExecutionAuthorization a = authorizations.findByWorkspaceAndIdForUpdate(w, authorizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorization not found"));
        RobotChangeProposal p = proposals.findByWorkspaceAndId(w, a.getProposalId()).orElseThrow();
        if (a.getStatus() == AuthorizationStatus.REVOKED) return summary(a, p);
        if (!a.isActive()) throw conflict("AUTHORIZATION_NOT_ACTIVE");
        Instant now = Instant.now(clock);
        a.revoke(membership.getUser(), now);
        authorizations.saveAndFlush(a);
        attempts.saveAndFlush(new AdaptiveExecutionAttempt(w, a.getId(), a.getProposalId(), a.getRobotId(),
                AttemptTrigger.AUTHORIZATION_REVOKED, AttemptResult.REVOKED, Reason.AUTHORIZATION_REVOKED.name(), null, null,
                RobotChangeProposalService.sha256("REVOKED|" + a.getId()), now));
        return summary(a, p);
    }

    private AuthorizationSummary summary(RobotAdaptiveExecutionAuthorization a, RobotChangeProposal p) {
        return new AuthorizationSummary(a.getId(), a.getProposalId(), a.getRobotId(), p.getTargetRobotNameSnapshot(), a.getFactor(),
                a.getFromPersonaId(), p.getCurrentPersonaNameSnapshot(), a.getToPersonaId(), p.getProposedPersonaNameSnapshot(),
                a.getSourceExperimentId(), a.getMaxExecutions(), a.getStatus(), a.getTerminalReason(), a.getValidFrom(),
                a.getExpiresAt(), a.getPolicyRevision(), a.getExecutionEngineVersion(), a.getCreatedBy().getId(), a.getCreatedAt(),
                a.getTerminatedAt(), a.getConsumedRevisionId(), a.getConsumedGuardrailEvaluationId());
    }

    private static ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
}
