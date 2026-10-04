package com.fdmultimedia.api.safety;

import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.analytics.PerformanceInsightProperties;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.experiments.*;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.ChangeType;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.ExecutionOrigin;
import com.fdmultimedia.api.robotchanges.RobotConfigurationRevision;
import com.fdmultimedia.api.robotchanges.RobotConfigurationRevisionRepository;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotRepository;
import com.fdmultimedia.api.safety.PostChangeSafetyModels.*;
import com.fdmultimedia.api.workspaces.Workspace;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 17M post-change safety monitoring. Observes real post-change outcomes of a successful forward adaptive PERSONA
 * change and compares them with a baseline frozen from the controlled Experiment that justified the change. It may
 * persist evidence and a {@code RollbackRecommendation}; it has no dependency that could mutate a Robot, an adaptive
 * policy or an execution authorization (enforced by {@code PostChangeSafetyArchitectureTest}).
 */
@Service
public class PostChangeSafetyService {
    private static final Logger log = LoggerFactory.getLogger(PostChangeSafetyService.class);

    /** RobotRuns created in the first 7 days of an epoch form the observation cohort (maximum D7 horizon). */
    public static final Duration COHORT_HORIZON = Duration.ofDays(7);
    /** 7-day cohort plus the 8-day D7 snapshot maturity ceiling, rounded up: monitoring stops afterwards. */
    public static final Duration MONITOR_HORIZON = Duration.ofDays(15);
    public static final String ANALYSIS_ENGINE = ExperimentAnalysisService.ANALYSIS_VERSION;
    static final int MAX_LIST = 50;
    static final int MAX_ROBOT_MONITORS = 20;
    private static final String LIMITATIONS = PostChangeSafetyModels.NON_CAUSAL_DISCLAIMER
            + " Evidence is observational, uses one frozen metric and provider, and TEST analytics are synthetic.";

    private final SafetyStore store;
    private final AuthService auth;
    private final RobotConfigurationRevisionRepository revisions;
    private final RobotRepository robots;
    private final ExperimentRepository experiments;
    private final ExperimentVariantRepository variants;
    private final ExperimentAnalysisStore experimentStore;
    private final PerformanceInsightProperties thresholds;
    private final Clock clock;

    public PostChangeSafetyService(SafetyStore store, AuthService auth, RobotConfigurationRevisionRepository revisions,
            RobotRepository robots, ExperimentRepository experiments, ExperimentVariantRepository variants,
            ExperimentAnalysisStore experimentStore, PerformanceInsightProperties thresholds, Clock clock) {
        this.store = store; this.auth = auth; this.revisions = revisions; this.robots = robots; this.experiments = experiments;
        this.variants = variants; this.experimentStore = experimentStore; this.thresholds = thresholds; this.clock = clock;
    }

    // ---- explicit evaluation + reads ----

    @Transactional
    public EvaluationRecord evaluateExplicit(AuthenticatedUser user, UUID robotId, UUID revisionId, String windowName) {
        Workspace workspace = auth.currentMembershipFor(user).getWorkspace();
        Window window = parseWindow(windowName);
        RobotConfigurationRevision revision = revisions.findByWorkspaceAndRobotIdAndId(workspace, robotId, revisionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot configuration revision not found"));
        if (!monitorable(revision)) throw new ResponseStatusException(HttpStatus.CONFLICT, "REVISION_NOT_MONITORABLE");
        store.lockRevision(revisionId);
        return evaluateLocked(revision, window, Trigger.EXPLICIT);
    }

    @Transactional(readOnly = true)
    public List<EvaluationRecord> evaluations(AuthenticatedUser user, UUID robotId, UUID revisionId, int limit) {
        Workspace workspace = auth.currentMembershipFor(user).getWorkspace();
        revisions.findByWorkspaceAndRobotIdAndId(workspace, robotId, revisionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot configuration revision not found"));
        return store.listEvaluations(workspace.getId(), revisionId, Math.max(1, Math.min(limit, MAX_LIST)));
    }

    @Transactional(readOnly = true)
    public RevisionSafety safety(AuthenticatedUser user, UUID robotId, UUID revisionId) {
        Workspace workspace = auth.currentMembershipFor(user).getWorkspace();
        RobotConfigurationRevision revision = revisions.findByWorkspaceAndRobotIdAndId(workspace, robotId, revisionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot configuration revision not found"));
        return store.findMonitor(workspace.getId(), revisionId).map(this::view).orElseGet(() -> pendingView(revision));
    }

    /** Bounded read model for the Robot configuration history: latest forward-revision monitors, no N+1 across revisions. */
    @Transactional(readOnly = true)
    public List<RevisionSafety> robotSafety(AuthenticatedUser user, UUID robotId) {
        Workspace workspace = auth.currentMembershipFor(user).getWorkspace();
        robots.findByWorkspaceAndId(workspace, robotId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot not found"));
        return store.monitorsForRobot(workspace.getId(), robotId, MAX_ROBOT_MONITORS).stream().map(this::view).toList();
    }

    private RevisionSafety view(MonitorRecord m) {
        RecommendationRecord recommendation = store.findLatestRecommendation(m.revisionId()).orElse(null);
        List<BaselineRecord> baselines = store.baselines(m.id());
        return new RevisionSafety(m.revisionId(), m.robotRevision(), m.executionOrigin(), m.authorizationId(), m.id(), m.status(),
                m.metric(), baselines.stream().map(BaselineRecord::provider).findFirst().orElse(null), m.epochStart(), m.epochEnd(),
                store.latestEvaluations(m.id()), baselines, recommendation, PostChangeSafetyModels.NON_CAUSAL_DISCLAIMER);
    }

    private RevisionSafety pendingView(RobotConfigurationRevision r) {
        return new RevisionSafety(r.getId(), r.getRevision(), r.getExecutionOrigin(), r.getExecutionAuthorizationId(), null, null, null,
                null, r.getCreatedAt(), null, List.of(), List.of(), null, PostChangeSafetyModels.NON_CAUSAL_DISCLAIMER);
    }

    // ---- reconciliation entry point (one transaction per revision) ----

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reconcileRevision(UUID revisionId) {
        RobotConfigurationRevision revision = revisions.findById(revisionId).orElse(null);
        if (revision == null || !monitorable(revision)) return 0;
        store.lockRevision(revisionId);
        boolean superseded = isSuperseded(revision, null).superseded();
        // A superseded epoch records exactly one canonical SUPERSEDED evaluation instead of one per window.
        List<Window> windows = superseded ? List.of(Window.H72) : PostChangeSafetyModels.MONITORED_WINDOWS;
        for (Window w : windows) evaluateLocked(revision, w, Trigger.RECONCILIATION);
        return windows.size();
    }

    // ---- core ----

    record Supersession(boolean superseded, Instant epochEnd) {}

    private Supersession isSuperseded(RobotConfigurationRevision revision, MonitorRecord monitor) {
        Optional<RobotConfigurationRevision> next = revisions.findFirstByRobotIdAndRevisionGreaterThanOrderByRevisionAsc(
                revision.getRobotId(), revision.getRevision());
        if (next.isPresent()) return new Supersession(true, next.get().getCreatedAt());
        Robot robot = robots.findById(revision.getRobotId()).orElse(null);
        UUID currentPersona = robot == null || robot.getPersona() == null ? null : robot.getPersona().getId();
        if (!Objects.equals(currentPersona, revision.getNewPersonaId())) {
            // A manual Robot edit changed the Persona outside the revision log: the epoch is no longer the monitored configuration.
            Instant end = monitor != null && monitor.epochEnd() != null ? monitor.epochEnd()
                    : robot != null && robot.getUpdatedAt() != null ? robot.getUpdatedAt() : Instant.now(clock);
            return new Supersession(true, end.isBefore(revision.getCreatedAt()) ? revision.getCreatedAt() : end);
        }
        return new Supersession(false, null);
    }

    static boolean monitorable(RobotConfigurationRevision r) {
        return r.getChangeType() == ChangeType.PERSONA_CHANGE && r.getNewPersonaId() != null
                && (r.getExecutionOrigin() == ExecutionOrigin.HUMAN_APPLY || r.getExecutionOrigin() == ExecutionOrigin.PREAUTHORIZED_AUTO_APPLY);
    }

    private static Window parseWindow(String name) {
        if (name == null || name.isBlank()) return Window.H72;
        try {
            Window w = Window.valueOf(name.trim().toUpperCase(Locale.ROOT));
            if (!PostChangeSafetyModels.MONITORED_WINDOWS.contains(w)) throw new IllegalArgumentException();
            return w;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_OBSERVATION_WINDOW");
        }
    }

    /** Caller holds the revision advisory lock. Returns the new evaluation, or the identical existing one (idempotent). */
    EvaluationRecord evaluateLocked(RobotConfigurationRevision revision, Window window, Trigger trigger) {
        Instant now = Instant.now(clock);
        UUID workspaceId = revision.getWorkspace().getId();
        MonitorRecord monitor = ensureMonitor(revision, now);
        refreshRecommendation(revision, monitor);

        Supersession supersession = isSuperseded(revision, monitor);
        if (supersession.superseded() && monitor.status() == MonitorStatus.MONITORING) {
            store.closeMonitor(monitor.id(), MonitorStatus.SUPERSEDED, CompletedReason.EPOCH_SUPERSEDED, supersession.epochEnd(), now);
            monitor = store.findMonitor(workspaceId, revision.getId()).orElse(monitor);
        }
        Instant epochEnd = monitor.epochEnd() != null ? monitor.epochEnd() : supersession.epochEnd();
        Instant cohortEnd = epochEnd != null && epochEnd.isBefore(monitor.epochStart().plus(COHORT_HORIZON)) ? epochEnd
                : monitor.epochStart().plus(COHORT_HORIZON);

        BaselineFacts baseline = null;
        String baselineProblem = null;
        CohortStats post = new CohortStats(0, 0, 0, 0, null, Set.of(), "NONE");
        if (!supersession.superseded()) {
            BaselineResolution resolved = resolveBaseline(monitor, window, now);
            baseline = resolved.facts();
            baselineProblem = resolved.problem();
            if (baseline != null) {
                List<CohortRow> rows = store.postChangeRows(workspaceId, revision.getRobotId(), revision.getNewPersonaId(), window,
                        monitor.metric(), monitor.epochStart(), cohortEnd, now);
                post = aggregate(rows);
            }
        }
        Thresholds t = new Thresholds(thresholds.getMinSampleSize(), thresholds.getMinCoverage(), thresholds.getMaterialDifferencePercent());
        Outcome outcome = PostChangeSafetyEvaluator.evaluate(supersession.superseded(), monitor.metric(), baseline, baselineProblem, post, t);
        String fingerprint = fingerprint(revision, window, monitor, baseline, baselineProblem, epochEnd, cohortEnd, post, outcome, t);

        EvaluationRecord evaluation = store.findEvaluationByFingerprint(monitor.id(), window, fingerprint).orElse(null);
        if (evaluation == null) {
            evaluation = buildEvaluation(revision, monitor, window, trigger, baseline, epochEnd, cohortEnd, post, outcome, t, fingerprint, now);
            store.insertEvaluation(evaluation);
            log.info("Post-change safety evaluation robotId={} revisionId={} window={} status={} evaluationId={}",
                    revision.getRobotId(), revision.getId(), window, outcome.status(), evaluation.id());
        }
        maybeRecommend(monitor, evaluation);

        if (monitor.status() == MonitorStatus.MONITORING) {
            if (!now.isBefore(monitor.epochStart().plus(MONITOR_HORIZON))) {
                store.closeMonitor(monitor.id(), MonitorStatus.COMPLETED, CompletedReason.HORIZON_REACHED, null, now);
            } else {
                store.touchMonitor(monitor.id(), now);
            }
        }
        return evaluation;
    }

    private MonitorRecord ensureMonitor(RobotConfigurationRevision r, Instant now) {
        UUID workspaceId = r.getWorkspace().getId();
        Optional<MonitorRecord> existing = store.findMonitor(workspaceId, r.getId());
        if (existing.isPresent()) return existing.get();
        Metric metric = null;
        if (r.getSourceExperimentId() != null) {
            metric = experiments.findById(r.getSourceExperimentId()).map(Experiment::getPrimaryMetric).orElse(null);
        }
        MonitorRecord monitor = new MonitorRecord(UUID.randomUUID(), workspaceId, r.getRobotId(), r.getId(), r.getRevision(),
                r.getExecutionOrigin(), r.getExecutionAuthorizationId(), r.getSourceProposalId(), r.getSourceExperimentId(),
                r.getPreviousPersonaId(), r.getPreviousPersonaNameSnapshot(), r.getNewPersonaId(), r.getNewPersonaNameSnapshot(),
                metric, r.getCreatedAt(), null, MonitorStatus.MONITORING, null, now, null);
        store.insertMonitor(monitor, now);
        return monitor;
    }

    // ---- baseline (frozen once per monitor/window, from the justifying Experiment) ----

    record BaselineResolution(BaselineFacts facts, String problem) {}

    private BaselineResolution resolveBaseline(MonitorRecord monitor, Window window, Instant now) {
        Optional<BaselineRecord> frozen = store.findBaseline(monitor.id(), window);
        if (frozen.isPresent()) return new BaselineResolution(facts(frozen.get()), null);
        if (monitor.experimentId() == null || monitor.metric() == null) return new BaselineResolution(null, "BASELINE_SOURCE_MISSING");
        Experiment experiment = experiments.findById(monitor.experimentId()).orElse(null);
        if (experiment == null || experiment.getFactor() != ExperimentFactor.PERSONA) return new BaselineResolution(null, "BASELINE_SOURCE_MISSING");
        if (experiment.getStatus() != ExperimentStatus.COMPLETED) return new BaselineResolution(null, "BASELINE_EXPERIMENT_NOT_COMPLETED");
        List<ExperimentVariant> rows = variants.findByExperimentOrderByVariantKeyAsc(experiment);
        ExperimentVariant a = rows.stream().filter(v -> v.getVariantKey() == ExperimentVariantKey.A).findFirst().orElse(null);
        ExperimentVariant b = rows.stream().filter(v -> v.getVariantKey() == ExperimentVariantKey.B).findFirst().orElse(null);
        if (a == null || b == null || !Objects.equals(a.getPersonaId(), monitor.previousPersonaId())
                || !Objects.equals(b.getPersonaId(), monitor.newPersonaId())) {
            return new BaselineResolution(null, "BASELINE_PERSONA_MISMATCH");
        }
        if (experimentStore.countAssignments(experiment.getId()) > ExperimentAnalysisStore.MAX_ANALYSIS_ASSIGNMENTS) {
            return new BaselineResolution(null, "BASELINE_SOURCE_TOO_LARGE");
        }
        List<AssignmentObservationRow> all = experimentStore.fetch(experiment.getId(), window, monitor.metric(), now);
        List<AssignmentObservationRow> variantA = all.stream().filter(r -> a.getId().equals(r.variantId())).toList();
        int assignments = variantA.size();
        int eligible = (int) variantA.stream().filter(AssignmentObservationRow::eligibleByAge).count();
        List<AssignmentObservationRow> included = variantA.stream().filter(r -> r.metricValue() != null).toList();
        int sample = included.size();
        Set<String> providers = new HashSet<>();
        included.stream().map(AssignmentObservationRow::provider).filter(Objects::nonNull).forEach(providers::add);
        if (providers.size() != 1) return new BaselineResolution(null, "BASELINE_PROVIDER_NOT_SINGLE");
        if (sample < thresholds.getMinSampleSize()) return new BaselineResolution(null, "BASELINE_SAMPLE_BELOW_MINIMUM");
        BigDecimal eligibleCoverage = ratio(sample, eligible);
        if (eligibleCoverage == null || eligibleCoverage.compareTo(thresholds.getMinCoverage()) < 0) {
            return new BaselineResolution(null, "BASELINE_COVERAGE_BELOW_MINIMUM");
        }
        BigDecimal mean = included.stream().map(AssignmentObservationRow::metricValue).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(sample), 4, RoundingMode.HALF_UP);
        String provider = providers.iterator().next();
        BigDecimal assignmentCoverage = ratio(sample, assignments);
        String fingerprint = PostChangeSafetyEvaluator.sha256(String.join("|", ANALYSIS_ENGINE, experiment.getId().toString(),
                a.getId().toString(), window.name(), monitor.metric().name(), provider, Integer.toString(assignments),
                Integer.toString(eligible), Integer.toString(sample), mean.toPlainString()));
        BaselineRecord record = new BaselineRecord(UUID.randomUUID(), monitor.workspaceId(), monitor.id(), window, experiment.getId(),
                ANALYSIS_ENGINE, monitor.metric(), provider, assignments, eligible, sample, eligibleCoverage,
                assignmentCoverage == null ? BigDecimal.ZERO : assignmentCoverage, mean, fingerprint, now);
        store.insertBaseline(record);
        return new BaselineResolution(facts(store.findBaseline(monitor.id(), window).orElse(record)), null);
    }

    private static BaselineFacts facts(BaselineRecord b) {
        return new BaselineFacts(b.id(), b.provider(), b.sample(), b.eligibleCoverage(), b.mean(), b.fingerprint());
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    // ---- cohort aggregation / fingerprint / records ----

    static CohortStats aggregate(List<CohortRow> rows) {
        int published = (int) rows.stream().filter(r -> r.publicationId() != null).count();
        int eligible = (int) rows.stream().filter(CohortRow::eligibleByAge).count();
        List<CohortRow> included = rows.stream().filter(r -> r.metricValue() != null).toList();
        BigDecimal mean = included.isEmpty() ? null : included.stream().map(CohortRow::metricValue).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(included.size()), 4, RoundingMode.HALF_UP);
        Set<String> providers = new TreeSet<>();
        included.stream().map(CohortRow::provider).filter(Objects::nonNull).forEach(providers::add);
        List<String> parts = rows.stream().map(r -> r.runId() + ":" + r.publicationId() + ":" + r.snapshotId() + ":"
                + (r.metricValue() == null ? "null" : r.metricValue().stripTrailingZeros().toPlainString())).sorted().toList();
        return new CohortStats(rows.size(), published, eligible, included.size(), mean, providers,
                PostChangeSafetyEvaluator.sha256(String.join(";", parts)));
    }

    private static String fingerprint(RobotConfigurationRevision r, Window window, MonitorRecord monitor, BaselineFacts baseline,
            String baselineProblem, Instant epochEnd, Instant cohortEnd, CohortStats post, Outcome outcome, Thresholds t) {
        return PostChangeSafetyEvaluator.sha256(String.join("|", PostChangeSafetyModels.ENGINE_VERSION, r.getId().toString(),
                window.name(), String.valueOf(monitor.metric()), baseline == null ? "NO_BASELINE:" + baselineProblem : baseline.fingerprint(),
                String.valueOf(epochEnd), String.valueOf(cohortEnd), post.digest(), Integer.toString(post.runs()),
                Integer.toString(post.published()), Integer.toString(post.eligible()), Integer.toString(post.sample()),
                String.valueOf(post.mean()), outcome.status().name(), String.join(",", outcome.reasons()),
                Integer.toString(t.minSample()), t.minCoverage().toPlainString(), t.materialPercent().toPlainString()));
    }

    private EvaluationRecord buildEvaluation(RobotConfigurationRevision r, MonitorRecord m, Window window, Trigger trigger,
            BaselineFacts baseline, Instant epochEnd, Instant cohortEnd, CohortStats post, Outcome o, Thresholds t,
            String fingerprint, Instant now) {
        boolean ready = o.status() == EvaluationStatus.READY_STABLE || o.status() == EvaluationStatus.READY_REGRESSION_OBSERVED;
        AdverseDirection direction = PostChangeSafetyEvaluator.directionOf(m.metric()).orElse(null);
        return new EvaluationRecord(UUID.randomUUID(), m.workspaceId(), m.id(), m.robotId(), m.revisionId(),
                store.nextEvaluationRevision(m.id(), window), PostChangeSafetyModels.ENGINE_VERSION, window,
                PostChangeSafetyModels.informational(window), o.status(), o.reasons(), trigger, m.metric(),
                baseline == null ? null : baseline.provider(), ready ? direction : null, m.executionOrigin(), m.authorizationId(),
                m.proposalId(), m.experimentId(), m.previousPersonaId(), m.newPersonaId(), baseline == null ? null : baseline.id(),
                baseline == null ? null : baseline.sample(), baseline == null ? null : baseline.coverage(),
                baseline == null ? null : baseline.mean(), m.epochStart(), epochEnd, cohortEnd, post.runs(), post.published(),
                post.eligible(), post.sample(), o.postCoverage() != null ? o.postCoverage() : post.coverage(),
                post.mean(), o.absoluteDifference(), o.relativePercent(), t.materialPercent(), t.minSample(),
                t.minCoverage(), fingerprint, now);
    }

    // ---- recommendation creation (never executes anything) ----

    private void maybeRecommend(MonitorRecord m, EvaluationRecord e) {
        if (e.status() != EvaluationStatus.READY_REGRESSION_OBSERVED || e.informational() || !PostChangeSafetyModels.decisionWindow(e.window())) return;
        if (store.findActiveRecommendation(m.revisionId()).isPresent()) return;
        // One recommendation per revision and decision window, ever: identical or repeated evidence for a window that was
        // already recommended (even if dismissed) is suppressed; the other decision window may still escalate once.
        if (store.findRecommendationForWindow(m.revisionId(), e.window()).isPresent()) return;
        // Evidence that already existed when a human resolved an earlier recommendation is not "materially new": it stays suppressed.
        RecommendationRecord latest = store.findLatestRecommendation(m.revisionId()).orElse(null);
        if (latest != null && latest.resolvedAt() != null && !e.evaluatedAt().isAfter(latest.resolvedAt())) return;
        String reason = "Material adverse post-change difference observed: %s mean %s vs frozen baseline %s (%s%%) over %s."
                .formatted(e.metric(), e.postValue().toPlainString(), e.baselineValue().toPlainString(),
                        e.relativeDifferencePercent() == null ? "n/a" : e.relativeDifferencePercent().toPlainString(), e.window());
        store.insertRecommendation(new RecommendationRecord(UUID.randomUUID(), m.workspaceId(), m.robotId(), m.revisionId(),
                m.robotRevision(), m.id(), e.id(), e.window(), RecommendationStatus.OPEN, e.metric(), e.provider(),
                m.previousPersonaId(), m.previousPersonaName(), m.newPersonaId(), m.newPersonaName(), m.executionOrigin(),
                m.authorizationId(), e.baselineSample(), e.baselineValue(), e.postSample(), e.postCoverage(), e.postValue(),
                e.absoluteDifference(), e.relativeDifferencePercent(), reason, LIMITATIONS, Instant.now(clock), null, null, null, null));
        log.info("Rollback recommendation opened robotId={} revisionId={} window={} evaluationId={}", m.robotId(), m.revisionId(), e.window(), e.id());
    }

    private void refreshRecommendation(RobotConfigurationRevision revision, MonitorRecord monitor) {
        store.findActiveRecommendation(revision.getId()).ifPresent(r -> RollbackRecommendationService.applyLifecycle(r, store, revisions, robots, clock));
    }
}
