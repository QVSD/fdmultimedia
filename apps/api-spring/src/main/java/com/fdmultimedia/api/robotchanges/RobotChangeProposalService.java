package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.analytics.DashboardQuery;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.experiments.*;
import com.fdmultimedia.api.optimization.OptimizationProposal;
import com.fdmultimedia.api.optimization.OptimizationProposalModels;
import com.fdmultimedia.api.optimization.OptimizationProposalRepository;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.personas.PersonaRepository;
import com.fdmultimedia.api.personas.PersonaStatus;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.*;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotAiPolicy;
import com.fdmultimedia.api.robots.RobotRepository;
import com.fdmultimedia.api.workspaces.Workspace;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 17I: the sole service in this codebase permitted to turn a completed,
 * Phase 17H-originated controlled Experiment into an actual Robot PERSONA
 * change — and only after an explicit human approval and a separate,
 * explicit Apply call (item: fundamental boundary). Analytics/recommendation/
 * Experiment-analysis/OptimizationProposal services never write to Robot
 * configuration directly; this class, and {@code Robot.applyPersona} itself,
 * are the only operational mutation path. Not autonomous optimization: no
 * method here ever calls apply/approve/reject/rollback on its own — every
 * state transition is caused by an explicit controller call made by a human
 * principal.
 */
@Service
public class RobotChangeProposalService {
    public static final String ENGINE_VERSION = "ROBOT_CHANGE_PROPOSALS_V1";
    private static final int MAX_LIST = 100;
    private static final String STANDING_DISCLAIMER =
            "This proposal reflects observed controlled-experiment evidence for this Experiment, this metric, and this "
            + "observation window only. It does not establish universal superiority of the proposed Persona, and approving "
            + "it does not itself change the Robot's configuration — a separate, explicit Apply action is required.";

    private final AuthService auth;
    private final RobotChangeProposalRepository proposals;
    private final RobotConfigurationRevisionRepository revisions;
    private final OptimizationProposalRepository optimizationProposals;
    private final ExperimentRepository experiments;
    private final ExperimentVariantRepository variants;
    private final ExperimentAnalysisService analysisService;
    private final RobotRepository robots;
    private final PersonaRepository personas;
    private final AdaptiveGuardrailService guardrails;
    private final Clock clock;

    public RobotChangeProposalService(AuthService auth, RobotChangeProposalRepository proposals,
            RobotConfigurationRevisionRepository revisions, OptimizationProposalRepository optimizationProposals,
            ExperimentRepository experiments, ExperimentVariantRepository variants, ExperimentAnalysisService analysisService,
            RobotRepository robots, PersonaRepository personas, AdaptiveGuardrailService guardrails, Clock clock) {
        this.auth = auth; this.proposals = proposals; this.revisions = revisions;
        this.optimizationProposals = optimizationProposals; this.experiments = experiments; this.variants = variants;
        this.analysisService = analysisService; this.robots = robots; this.personas = personas; this.guardrails=guardrails; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Summary> list(AuthenticatedUser principal, int limit) {
        Workspace workspace = auth.currentMembershipFor(principal).getWorkspace();
        int bounded = Math.max(1, Math.min(limit, MAX_LIST));
        return proposals.findByWorkspaceOrderByCreatedAtDesc(workspace, PageRequest.of(0, bounded)).stream()
                .map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public Summary get(AuthenticatedUser principal, UUID id) {
        Workspace workspace = auth.currentMembershipFor(principal).getWorkspace();
        return summary(proposals.findByWorkspaceAndId(workspace, id).orElseThrow(() -> notFound("Robot change proposal not found")));
    }

    @Transactional(readOnly = true)
    public Eligibility eligibility(AuthenticatedUser principal, UUID sourceOptimizationProposalId, UUID targetRobotId) {
        Workspace workspace = auth.currentMembershipFor(principal).getWorkspace();
        Bundle bundle = gather(principal, workspace, sourceOptimizationProposalId, targetRobotId);
        return new Eligibility(bundle.reasonCode == null, bundle.reasonCode, sourceOptimizationProposalId, targetRobotId);
    }

    @Transactional
    public Summary create(AuthenticatedUser principal, CreateRequest request) {
        var membership = auth.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        Bundle bundle = gather(principal, workspace, request.sourceOptimizationProposalId(), request.targetRobotId());
        if (bundle.reasonCode != null) throw conflict(bundle.reasonCode);

        Robot robot = bundle.robot;
        Persona current = robot.getPersona();
        Persona proposed = bundle.candidatePersona;
        ExperimentPopulationAnalysis assigned = bundle.analysis.assignedObserved();
        ExperimentEffectEstimate effect = assigned.effect();
        DashboardQuery.Metric metric = bundle.experiment.getPrimaryMetric();
        DashboardQuery.Window window = bundle.experiment.getTargetObservationWindow();
        String expectedFingerprint = robotConfigFingerprint(robot.getId(), current == null ? null : current.getId());
        String currentFp = personaFingerprint(current);
        String proposedFp = personaFingerprint(proposed);
        String limitations = bounded(String.join("\n", bundle.analysis.limitations()), 2000);
        String rationale = bounded("Based on completed Experiment \"" + bundle.experiment.getName() + "\" (ASSIGNED_OBSERVED "
                + "population, " + metric.name() + " over " + window.name() + "), the current and proposed Persona treatments "
                + "were compared under randomized assignment. " + STANDING_DISCLAIMER, 1000);
        String fingerprint = sha256(String.join("|", ENGINE_VERSION, request.sourceOptimizationProposalId().toString(),
                bundle.experiment.getId().toString(), robot.getId().toString(), expectedFingerprint,
                n(current == null ? null : current.getId()), proposed.getId().toString(), metric.name(), window.name(),
                "ASSIGNED_OBSERVED", n(effect.pValue()), n(effect.absoluteMeanDifference()),
                String.valueOf(assigned.variantA().metricSampleCount()), String.valueOf(assigned.variantB().metricSampleCount())));

        RobotChangeProposal p = new RobotChangeProposal(workspace, request.sourceOptimizationProposalId(), bundle.experiment.getId(),
                robot.getId(), robot.getName(), current == null ? null : current.getId(), current == null ? null : current.getName(),
                currentFp, proposed.getId(), proposed.getName(), proposedFp, expectedFingerprint, metric, window,
                (int) assigned.variantA().metricSampleCount(), (int) assigned.variantB().metricSampleCount(),
                assigned.variantA().assignmentOutcomeCoverage(), assigned.variantB().assignmentOutcomeCoverage(),
                effect.absoluteMeanDifference(), effect.relativeMeanDifferencePercent(), effect.standardError(),
                effect.degreesOfFreedom(), effect.confidenceIntervalLower(), effect.confidenceIntervalUpper(),
                effect.confidenceIntervalIncludesZero(), effect.pValue(), effect.standardizedEffectSize(), limitations,
                rationale, fingerprint, membership.getUser(), Instant.now(clock));
        proposals.saveAndFlush(p);
        guardrails.evaluateAndPersist(workspace,robot,p,RobotAdaptivePolicyModels.Trigger.CREATE);
        return summary(p);
    }

    @Transactional
    public Summary approve(AuthenticatedUser principal, UUID id) {
        RobotChangeProposal p = lock(principal, id);
        if (p.getStatus() != Status.READY_FOR_REVIEW) throw conflict("PROPOSAL_NOT_READY_FOR_REVIEW");
        p.approve(Instant.now(clock));
        proposals.saveAndFlush(p);
        Robot robot=robots.findByWorkspaceAndId(p.getWorkspace(),p.getTargetRobotId()).orElseThrow(()->notFound("Robot not found"));
        guardrails.evaluateAndPersist(p.getWorkspace(),robot,p,RobotAdaptivePolicyModels.Trigger.APPROVE);
        return summary(p);
    }

    @Transactional
    public Summary reject(AuthenticatedUser principal, UUID id) {
        RobotChangeProposal p = lock(principal, id);
        if (p.getStatus() != Status.READY_FOR_REVIEW) throw conflict("PROPOSAL_NOT_READY_FOR_REVIEW");
        p.reject(Instant.now(clock));
        proposals.saveAndFlush(p);
        return summary(p);
    }

    /**
     * Atomic, all-or-nothing (item: Apply transaction). Idempotent by
     * construction: a proposal can only ever transition APPROVED -&gt;
     * APPLIED once; a repeat call observes {@code APPLIED} already and
     * returns the same result without creating a second revision. Under
     * concurrency, every simultaneous caller blocks on the same pessimistic
     * proposal-row lock; exactly one proceeds, the rest see the post-Apply
     * state once the lock is released.
     */
    @Transactional
    public Summary apply(AuthenticatedUser principal, UUID id) {
        var membership = auth.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        RobotChangeProposal p = proposals.findByWorkspaceAndIdForUpdate(workspace, id).orElseThrow(() -> notFound("Robot change proposal not found"));
        if (p.getStatus() == Status.APPLIED) return summary(p);
        if (p.getStatus() != Status.APPROVED) throw conflict("PROPOSAL_NOT_APPROVED");

        Robot robot = robots.findByWorkspaceAndIdForUpdate(workspace, p.getTargetRobotId()).orElseThrow(() -> notFound("Robot not found"));
        Persona current = robot.getPersona();
        String currentFingerprint = robotConfigFingerprint(robot.getId(), current == null ? null : current.getId());
        if (!currentFingerprint.equals(p.getExpectedRobotConfigFingerprint())) {
            // Item: a divergence is a valid, persisted outcome (STALE), never a rolled-back
            // exception — mirrors OptimizationProposalService.materialize()'s identical
            // stale-detection pattern. Throwing here would roll back markStale() along with
            // it, since @Transactional reverts the whole method on an uncaught exception.
            p.markStale();
            proposals.saveAndFlush(p);
            return summary(p);
        }
        Persona candidate = personas.findByWorkspaceAndId(workspace, p.getProposedPersonaId()).orElse(null);
        if (candidate == null || candidate.getStatus() != PersonaStatus.ACTIVE) {
            p.markStale();
            proposals.saveAndFlush(p);
            return summary(p);
        }

        AdaptiveGuardrailEvaluation evaluation=guardrails.evaluateAndPersist(workspace,robot,p,RobotAdaptivePolicyModels.Trigger.APPLY);
        if(!evaluation.isEligible())return summary(p);

        Instant now = Instant.now(clock);
        robot.applyPersona(candidate, now);
        robots.saveAndFlush(robot);

        String newFingerprint = robotConfigFingerprint(robot.getId(), candidate.getId());
        int nextRevision = revisions.maxRevision(robot.getId()) + 1;
        RobotConfigurationRevision revision = new RobotConfigurationRevision(workspace, robot.getId(), nextRevision,
                ChangeType.PERSONA_CHANGE, current == null ? null : current.getId(), current == null ? null : current.getName(),
                candidate.getId(), candidate.getName(), currentFingerprint, newFingerprint, p.getId(), p.getSourceExperimentId(),
                null, membership.getUser(), null, now,evaluation.getId(),evaluation.getPolicyRevision(),evaluation.getEngineVersion());
        revisions.saveAndFlush(revision);

        p.markApplied(now);
        proposals.saveAndFlush(p);
        return summary(p);
    }

    @Transactional(readOnly = true)
    public List<RevisionSummary> revisionsForRobot(AuthenticatedUser principal, UUID robotId) {
        Workspace workspace = auth.currentMembershipFor(principal).getWorkspace();
        robots.findByWorkspaceAndId(workspace, robotId).orElseThrow(() -> notFound("Robot not found"));
        return revisions.findByWorkspaceAndRobotIdOrderByRevisionDesc(workspace, robotId).stream().map(this::revisionSummary).toList();
    }

    /**
     * Explicit human rollback only (never automatic/metric-triggered): the
     * target revision must still be the Robot's current (latest) revision —
     * a rollback of a superseded revision is rejected outright rather than
     * reinterpreted ("no time travel"). Idempotent: a repeat call finds the
     * rollback revision already created by a prior call and returns it
     * instead of creating a duplicate.
     */
    @Transactional
    public RevisionSummary rollback(AuthenticatedUser principal, UUID robotId, UUID revisionId, RollbackRequest request) {
        var membership = auth.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        Robot robot = robots.findByWorkspaceAndIdForUpdate(workspace, robotId).orElseThrow(() -> notFound("Robot not found"));
        RobotConfigurationRevision target = revisions.findByWorkspaceAndRobotIdAndId(workspace, robotId, revisionId)
                .orElseThrow(() -> notFound("Robot configuration revision not found"));

        Optional<RobotConfigurationRevision> existingRollback = revisions.findByRobotIdAndRollbackOfRevisionId(robotId, revisionId);
        if (existingRollback.isPresent()) return revisionSummary(existingRollback.get());

        int maxRevision = revisions.maxRevision(robotId);
        if (target.getRevision() != maxRevision) throw conflict("ROLLBACK_TARGET_NOT_CURRENT");

        Persona current = robot.getPersona();
        String currentFingerprint = robotConfigFingerprint(robotId, current == null ? null : current.getId());
        if (!currentFingerprint.equals(target.getNewConfigFingerprint())) throw conflict("ROBOT_DIVERGED_SINCE_REVISION");

        UUID restoreId = target.getPreviousPersonaId();
        Persona restore = null;
        if (restoreId != null) {
            restore = personas.findByWorkspaceAndId(workspace, restoreId).orElseThrow(() -> conflict("ROLLBACK_PERSONA_NOT_FOUND"));
            if (restore.getStatus() != PersonaStatus.ACTIVE) throw conflict("ROLLBACK_PERSONA_ARCHIVED");
        }

        Instant now = Instant.now(clock);
        robot.applyPersona(restore, now);
        robots.saveAndFlush(robot);

        String newFingerprint = robotConfigFingerprint(robotId, restore == null ? null : restore.getId());
        RobotConfigurationRevision rollbackRevision = new RobotConfigurationRevision(workspace, robotId, maxRevision + 1,
                ChangeType.ROLLBACK, current == null ? null : current.getId(), current == null ? null : current.getName(),
                restore == null ? null : restore.getId(), restore == null ? null : restore.getName(), currentFingerprint,
                newFingerprint, target.getSourceProposalId(), target.getSourceExperimentId(), revisionId, membership.getUser(),
                request == null ? null : bounded(request.reason(), 500), now);
        revisions.saveAndFlush(rollbackRevision);

        if (target.getChangeType() == ChangeType.PERSONA_CHANGE && target.getSourceProposalId() != null) {
            proposals.findByWorkspaceAndIdForUpdate(workspace, target.getSourceProposalId()).ifPresent(proposal -> {
                if (proposal.getStatus() == Status.APPLIED) {
                    proposal.markRolledBack(now);
                    proposals.saveAndFlush(proposal);
                }
            });
        }
        return revisionSummary(rollbackRevision);
    }

    // ---- shared eligibility resolution ----

    private record Bundle(Workspace workspace, OptimizationProposal source, Experiment experiment, Robot robot,
            Persona candidatePersona, ExperimentAnalysisResponse analysis, String reasonCode) {}

    private Bundle gather(AuthenticatedUser principal, Workspace workspace, UUID sourceOptimizationProposalId, UUID targetRobotId) {
        OptimizationProposal source = optimizationProposals.findByWorkspaceAndId(workspace, sourceOptimizationProposalId)
                .orElseThrow(() -> notFound("Optimization proposal not found"));
        Robot robot = robots.findByWorkspaceAndId(workspace, targetRobotId).orElseThrow(() -> notFound("Robot not found"));

        if (source.getStatus() != OptimizationProposalModels.Status.MATERIALIZED || source.getMaterializedExperimentId() == null) {
            return new Bundle(workspace, source, null, robot, null, null, "SOURCE_PROPOSAL_NOT_MATERIALIZED");
        }
        Experiment experiment = experiments.findByWorkspaceAndId(workspace, source.getMaterializedExperimentId())
                .orElseThrow(() -> notFound("Source experiment not found"));
        if (experiment.getFactor() != ExperimentFactor.PERSONA) {
            return new Bundle(workspace, source, experiment, robot, null, null, "UNSUPPORTED_EXPERIMENT_FACTOR");
        }
        if (experiment.getStatus() != ExperimentStatus.COMPLETED) {
            return new Bundle(workspace, source, experiment, robot, null, null, "EXPERIMENT_NOT_COMPLETED");
        }
        List<ExperimentVariant> variantRows = variants.findByExperimentOrderByVariantKeyAsc(experiment);
        if (variantRows.size() != 2) {
            return new Bundle(workspace, source, experiment, robot, null, null, "EXPERIMENT_VARIANTS_UNAVAILABLE");
        }
        ExperimentVariant variantB = variantRows.stream().filter(v -> v.getVariantKey() == ExperimentVariantKey.B).findFirst().orElseThrow();
        if (!variantB.getPersonaId().equals(source.getCandidatePersonaId())) {
            return new Bundle(workspace, source, experiment, robot, null, null, "SOURCE_PROPOSAL_VARIANT_MISMATCH");
        }
        if (robot.getAiPolicy() == RobotAiPolicy.NO_AI) {
            return new Bundle(workspace, source, experiment, robot, null, null, "TARGET_ROBOT_DOES_NOT_SUPPORT_PERSONA");
        }
        if (!experiment.getId().equals(robot.getExperimentId())) {
            return new Bundle(workspace, source, experiment, robot, null, null, "TARGET_ROBOT_NOT_ENROLLED");
        }
        Persona candidate = personas.findByWorkspaceAndId(workspace, source.getCandidatePersonaId()).orElse(null);
        if (candidate == null || candidate.getStatus() != PersonaStatus.ACTIVE) {
            return new Bundle(workspace, source, experiment, robot, null, null, "CANDIDATE_PERSONA_UNAVAILABLE");
        }
        ExperimentAnalysisResponse analysis = analysisService.analyze(principal, experiment.getId());
        if (analysis.assignedObserved().status() != AnalysisStatus.READY) {
            return new Bundle(workspace, source, experiment, robot, candidate, analysis, "ANALYSIS_NOT_READY");
        }
        return new Bundle(workspace, source, experiment, robot, candidate, analysis, null);
    }

    // ---- mapping / fingerprints ----

    private RobotChangeProposal lock(AuthenticatedUser principal, UUID id) {
        Workspace workspace = auth.currentMembershipFor(principal).getWorkspace();
        return proposals.findByWorkspaceAndIdForUpdate(workspace, id).orElseThrow(() -> notFound("Robot change proposal not found"));
    }

    private Summary summary(RobotChangeProposal p) {
        return new Summary(p.getId(), p.getSourceOptimizationProposalId(), p.getSourceExperimentId(), p.getEngineVersion(),
                p.getFactor(), p.getStatus(), p.getTargetRobotId(), p.getTargetRobotNameSnapshot(), p.getCurrentPersonaId(),
                p.getCurrentPersonaNameSnapshot(), p.getProposedPersonaId(), p.getProposedPersonaNameSnapshot(),
                p.getExpectedRobotConfigFingerprint(), p.getAnalysisEngineVersion(), p.getMetric(), p.getObservationWindow(),
                p.getPopulation(), p.getBaselineSampleCount(), p.getCandidateSampleCount(), p.getBaselineCoverage(),
                p.getCandidateCoverage(), p.getAbsoluteMeanDifference(), p.getRelativeMeanDifferencePercent(),
                p.getStandardError(), p.getDegreesOfFreedom(), p.getConfidenceIntervalLower(), p.getConfidenceIntervalUpper(),
                p.getConfidenceIntervalIncludesZero(), p.getPValue(), p.getStandardizedEffectSize(),
                List.of(p.getLimitations().split("\n")), p.getRationale(), p.getProposalFingerprint(), p.getCreatedAt(),
                p.getReviewedAt(), p.getAppliedAt(), p.getRolledBackAt());
    }

    private RevisionSummary revisionSummary(RobotConfigurationRevision r) {
        return new RevisionSummary(r.getId(), r.getRobotId(), r.getRevision(), r.getChangeType(), r.getPreviousPersonaId(),
                r.getPreviousPersonaNameSnapshot(), r.getNewPersonaId(), r.getNewPersonaNameSnapshot(),
                r.getPreviousConfigFingerprint(), r.getNewConfigFingerprint(), r.getSourceProposalId(), r.getSourceExperimentId(),
                r.getRollbackOfRevisionId(), r.getReason(), r.getCreatedAt(),r.getGuardrailEvaluationId(),
                r.getAdaptivePolicyRevision(),r.getGuardrailEngineVersion());
    }

    /**
     * Minimum sufficient fingerprint for the one factor this phase can
     * change (item: configuration fingerprint) — Robot id plus the current
     * Persona identity only, deliberately excluding {@code Robot.updatedAt}
     * or any other field. A manual edit to an unrelated axis (schedule,
     * cadence, AI policy, etc.) must not stale a pending Persona-change
     * proposal; only a Persona change itself — through this service or
     * through the ordinary Robot update endpoint — changes this value.
     */
    static String robotConfigFingerprint(UUID robotId, UUID personaId) {
        return sha256(robotId + "|" + (personaId == null ? "NONE" : personaId));
    }

    /** Identical algorithm to {@code OptimizationProposalService.personaFingerprint} (item: not shared cross-package to avoid touching Phase 17H code). */
    static String personaFingerprint(Persona p) {
        if (p == null) return sha256("NONE");
        return sha256(String.join("|", p.getId().toString(), n(p.getName()), n(p.getDescription()), p.getDefaultLanguage().name(),
                p.getDefaultTone().name(), n(p.getAudience()), n(p.getVoiceDescription()), n(p.getStyleGuidelines()),
                n(p.getAvoidGuidelines()), n(p.getHashtagGuidelines()), n(p.getExampleCopy())));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String n(String v) { return v == null ? "" : v; }
    private static String n(UUID v) { return v == null ? "NONE" : v.toString(); }
    private static String n(BigDecimal v) { return v == null ? "NA" : v.toPlainString(); }
    private static String bounded(String value, int max) { return value == null ? null : (value.length() <= max ? value : value.substring(0, max)); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
}
