package com.fdmultimedia.api.safety;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.analytics.PerformanceInsightProperties;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.experiments.*;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.ChangeType;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.ExecutionOrigin;
import com.fdmultimedia.api.robotchanges.RobotConfigurationRevision;
import com.fdmultimedia.api.robotchanges.RobotConfigurationRevisionRepository;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotRepository;
import com.fdmultimedia.api.safety.PostChangeSafetyModels.*;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class PostChangeSafetyServiceTest {
    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final Instant APPLIED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private final MutableClock clock = new MutableClock(APPLIED_AT.plus(Duration.ofDays(9)));
    private final FakeSafetyStore store = new FakeSafetyStore();
    private final AuthService auth = mock(AuthService.class);
    private final RobotConfigurationRevisionRepository revisions = mock(RobotConfigurationRevisionRepository.class);
    private final RobotRepository robots = mock(RobotRepository.class);
    private final ExperimentRepository experiments = mock(ExperimentRepository.class);
    private final ExperimentVariantRepository variants = mock(ExperimentVariantRepository.class);
    private final ExperimentAnalysisStore experimentStore = mock(ExperimentAnalysisStore.class);
    private final PerformanceInsightProperties thresholds = new PerformanceInsightProperties();
    private final PostChangeSafetyService service = new PostChangeSafetyService(store, auth, revisions, robots, experiments, variants,
            experimentStore, thresholds, clock);

    private final Workspace workspace = new Workspace("Test", "test");
    private final AppUser owner = new AppUser("owner@example.test", "hash", "Owner");
    private final UUID robotId = UUID.randomUUID();
    private final UUID personaA = UUID.randomUUID();
    private final UUID personaB = UUID.randomUUID();
    private final UUID personaC = UUID.randomUUID();
    private final UUID experimentId = UUID.randomUUID();
    private final UUID variantAId = UUID.randomUUID();
    private RobotConfigurationRevision revision;
    private BigDecimal baselineMean = new BigDecimal("100");

    @BeforeEach
    void setUp() {
        revision = revision(1, ChangeType.PERSONA_CHANGE, personaA, personaB, ExecutionOrigin.HUMAN_APPLY, null, APPLIED_AT);
        stubRevision(revision);
        when(revisions.findFirstByRobotIdAndRevisionGreaterThanOrderByRevisionAsc(robotId, 1)).thenReturn(Optional.empty());
        useCurrentPersona(personaB);

        Experiment experiment = mock(Experiment.class);
        when(experiment.getId()).thenReturn(experimentId);
        when(experiment.getFactor()).thenReturn(ExperimentFactor.PERSONA);
        when(experiment.getStatus()).thenReturn(ExperimentStatus.COMPLETED);
        when(experiment.getPrimaryMetric()).thenReturn(Metric.TOTAL_INTERACTIONS);
        when(experiments.findById(experimentId)).thenReturn(Optional.of(experiment));
        ExperimentVariant a = mock(ExperimentVariant.class);
        when(a.getId()).thenReturn(variantAId);
        when(a.getVariantKey()).thenReturn(ExperimentVariantKey.A);
        when(a.getPersonaId()).thenReturn(personaA);
        ExperimentVariant b = mock(ExperimentVariant.class);
        when(b.getId()).thenReturn(UUID.randomUUID());
        when(b.getVariantKey()).thenReturn(ExperimentVariantKey.B);
        when(b.getPersonaId()).thenReturn(personaB);
        when(variants.findByExperimentOrderByVariantKeyAsc(experiment)).thenReturn(List.of(a, b));
        when(experimentStore.countAssignments(experimentId)).thenReturn(20L);
        when(experimentStore.fetch(eq(experimentId), any(), eq(Metric.TOTAL_INTERACTIONS), any())).thenAnswer(i -> baselineRows(baselineMean));
        when(auth.currentMembershipFor(any())).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        store.cohort = w -> cohort(10, "80");
    }

    // ---- helpers ----

    private RobotConfigurationRevision revision(int number, ChangeType type, UUID from, UUID to, ExecutionOrigin origin, UUID authorization, Instant at) {
        return new RobotConfigurationRevision(workspace, robotId, number, type, from, "From", to, "To", "fp-from", "fp-to",
                UUID.randomUUID(), experimentId, null, new AppUser("a@example.test", "h", "A"), null, at, null, 1, "E", origin, authorization, null);
    }

    private void stubRevision(RobotConfigurationRevision r) {
        when(revisions.findById(r.getId())).thenReturn(Optional.of(r));
        when(revisions.findByWorkspaceAndRobotIdAndId(workspace, robotId, r.getId())).thenReturn(Optional.of(r));
    }

    private void useCurrentPersona(UUID personaId) {
        Persona persona = mock(Persona.class);
        when(persona.getId()).thenReturn(personaId);
        Robot robot = mock(Robot.class);
        when(robot.getPersona()).thenReturn(persona);
        when(robot.getUpdatedAt()).thenReturn(clock.now);
        when(robots.findById(robotId)).thenReturn(Optional.of(robot));
    }

    private List<AssignmentObservationRow> baselineRows(BigDecimal value) {
        List<AssignmentObservationRow> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(new AssignmentObservationRow(UUID.randomUUID(), variantAId, "COMPLETED", UUID.randomUUID(), APPLIED_AT.minusSeconds(900_000),
                    "TEST", false, true, false, UUID.randomUUID(), value));
        }
        return rows;
    }

    private List<CohortRow> cohort(int n, String value) {
        List<CohortRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new CohortRow(new UUID(0, i), new UUID(1, i), APPLIED_AT.plusSeconds(3600L * (i + 1)), "TEST", true, new UUID(2, i), new BigDecimal(value)));
        }
        return rows;
    }

    private List<EvaluationRecord> evaluationsOf(Window w) {
        return store.evaluations.stream().filter(e -> e.window() == w).toList();
    }

    // ---- monitorable origins ----

    @Test
    void humanApplyAndPreauthorizedAutoApplyAreMonitoredRollbackIsNeverAForwardTarget() {
        assertThat(PostChangeSafetyService.monitorable(revision)).isTrue();
        RobotConfigurationRevision auto = revision(2, ChangeType.PERSONA_CHANGE, personaA, personaB, ExecutionOrigin.PREAUTHORIZED_AUTO_APPLY, UUID.randomUUID(), APPLIED_AT);
        assertThat(PostChangeSafetyService.monitorable(auto)).isTrue();
        RobotConfigurationRevision rollback = revision(3, ChangeType.ROLLBACK, personaB, personaA, ExecutionOrigin.HUMAN_ROLLBACK, null, APPLIED_AT);
        assertThat(PostChangeSafetyService.monitorable(rollback)).isFalse();
        stubRevision(rollback);
        assertThat(service.reconcileRevision(rollback.getId())).isZero();
        assertThat(store.monitors).isEmpty();
    }

    @Test
    void autoAppliedRevisionIsMonitoredIdenticallyAndKeepsAuthorizationProvenance() {
        UUID authorization = UUID.randomUUID();
        RobotConfigurationRevision auto = revision(1, ChangeType.PERSONA_CHANGE, personaA, personaB, ExecutionOrigin.PREAUTHORIZED_AUTO_APPLY, authorization, APPLIED_AT);
        stubRevision(auto);
        service.reconcileRevision(auto.getId());
        MonitorRecord m = store.monitors.values().iterator().next();
        assertThat(m.executionOrigin()).isEqualTo(ExecutionOrigin.PREAUTHORIZED_AUTO_APPLY);
        assertThat(m.authorizationId()).isEqualTo(authorization);
        assertThat(store.recommendations.values()).allSatisfy(r -> assertThat(r.authorizationId()).isEqualTo(authorization));
        assertThat(store.evaluations).hasSize(3);
    }

    // ---- baseline ----

    @Test
    void baselineIsFrozenOncePerWindowAndNeverMovesOnReevaluation() {
        service.reconcileRevision(revision.getId());
        assertThat(store.baselines).hasSize(3);
        BigDecimal frozen = store.findBaseline(store.monitors.values().iterator().next().id(), Window.H72).orElseThrow().mean();
        assertThat(frozen).isEqualByComparingTo("100");
        baselineMean = new BigDecimal("40"); // the Experiment data "moves" later; the frozen baseline must not
        store.cohort = w -> cohort(10, "95");
        service.reconcileRevision(revision.getId());
        assertThat(store.baselines).hasSize(3);
        assertThat(evaluationsOf(Window.H72).get(evaluationsOf(Window.H72).size() - 1).baselineValue()).isEqualByComparingTo("100");
    }

    @Test
    void baselineComesFromPreviousPersonaVariantOfTheJustifyingExperiment() {
        service.reconcileRevision(revision.getId());
        BaselineRecord b = store.baselines.get(0);
        assertThat(b.experimentId()).isEqualTo(experimentId);
        assertThat(b.metric()).isEqualTo(Metric.TOTAL_INTERACTIONS);
        assertThat(b.provider()).isEqualTo("TEST");
        assertThat(b.sample()).isEqualTo(10);
        assertThat(b.analysisEngineVersion()).isEqualTo(ExperimentAnalysisService.ANALYSIS_VERSION);
    }

    @Test
    void baselineNotAttributableToThePreviousPersonaIsUnavailableNeverInvented() {
        UUID other = UUID.randomUUID();
        RobotConfigurationRevision r = revision(1, ChangeType.PERSONA_CHANGE, other, personaB, ExecutionOrigin.HUMAN_APPLY, null, APPLIED_AT);
        stubRevision(r);
        service.reconcileRevision(r.getId());
        assertThat(store.evaluations).allSatisfy(e -> {
            assertThat(e.status()).isEqualTo(EvaluationStatus.BASELINE_UNAVAILABLE);
            assertThat(e.reasons()).containsExactly("BASELINE_PERSONA_MISMATCH");
        });
        assertThat(store.recommendations).isEmpty();
        assertThat(store.cohortQueries).isZero();
    }

    // ---- epochs ----

    @Test
    void postChangeCohortIsBoundedToTheNewPersonaEpochAndSevenDayHorizon() {
        service.reconcileRevision(revision.getId());
        assertThat(store.lastEpochStart).isEqualTo(APPLIED_AT);
        assertThat(store.lastPersona).isEqualTo(personaB);
        assertThat(store.lastCohortEnd).isEqualTo(APPLIED_AT.plus(Duration.ofDays(7)));
    }

    @Test
    void laterRevisionEndsTheEpochStopsEvidenceAndNeverContaminatesTheNextEpoch() {
        RobotConfigurationRevision next = revision(2, ChangeType.PERSONA_CHANGE, personaB, personaC, ExecutionOrigin.HUMAN_APPLY, null, APPLIED_AT.plus(Duration.ofDays(2)));
        when(revisions.findFirstByRobotIdAndRevisionGreaterThanOrderByRevisionAsc(robotId, 1)).thenReturn(Optional.of(next));
        service.reconcileRevision(revision.getId());
        MonitorRecord first = store.monitors.values().iterator().next();
        assertThat(first.status()).isEqualTo(MonitorStatus.SUPERSEDED);
        assertThat(first.epochEnd()).isEqualTo(next.getCreatedAt());
        assertThat(store.cohortQueries).as("no evidence is accumulated for a superseded epoch").isZero();
        assertThat(store.evaluations).hasSize(1);
        assertThat(store.evaluations.get(0).status()).isEqualTo(EvaluationStatus.SUPERSEDED);

        // the B->C epoch is monitored on its own: it starts at its own revision for persona C, and the A-vs-B Experiment is
        // never reused as its baseline (that comparison does not describe B->C)
        stubRevision(next);
        when(revisions.findFirstByRobotIdAndRevisionGreaterThanOrderByRevisionAsc(robotId, 2)).thenReturn(Optional.empty());
        useCurrentPersona(personaC);
        service.reconcileRevision(next.getId());
        MonitorRecord second = store.findMonitor(workspace.getId(), next.getId()).orElseThrow();
        assertThat(second.epochStart()).isEqualTo(next.getCreatedAt());
        assertThat(second.newPersonaId()).isEqualTo(personaC);
        assertThat(second.status()).isEqualTo(MonitorStatus.MONITORING);
        assertThat(store.evaluations.stream().filter(e -> e.revisionId().equals(next.getId())))
                .allSatisfy(e -> assertThat(e.status()).isEqualTo(EvaluationStatus.BASELINE_UNAVAILABLE));
        assertThat(store.cohortQueries).isZero();
    }

    @Test
    void rollbackAfterTheChangeEndsTheEpoch() {
        RobotConfigurationRevision rollback = revision(2, ChangeType.ROLLBACK, personaB, personaA, ExecutionOrigin.HUMAN_ROLLBACK, null, APPLIED_AT.plus(Duration.ofDays(1)));
        when(revisions.findFirstByRobotIdAndRevisionGreaterThanOrderByRevisionAsc(robotId, 1)).thenReturn(Optional.of(rollback));
        service.reconcileRevision(revision.getId());
        assertThat(store.monitors.values().iterator().next().status()).isEqualTo(MonitorStatus.SUPERSEDED);
        assertThat(store.cohortQueries).isZero();
    }

    @Test
    void manualPersonaEditOutsideTheRevisionLogSupersedesTheEpoch() {
        useCurrentPersona(personaC);
        service.reconcileRevision(revision.getId());
        assertThat(store.monitors.values().iterator().next().status()).isEqualTo(MonitorStatus.SUPERSEDED);
        assertThat(store.recommendations).isEmpty();
    }

    // ---- statuses / windows ----

    @Test
    void h24IsInformationalAndNeverCreatesARecommendationEvenWhenAdverse() {
        service.reconcileRevision(revision.getId());
        EvaluationRecord h24 = evaluationsOf(Window.H24).get(0);
        assertThat(h24.informational()).isTrue();
        assertThat(h24.status()).isEqualTo(EvaluationStatus.READY_REGRESSION_OBSERVED);
        assertThat(store.recommendations.values()).noneMatch(r -> r.window() == Window.H24);
    }

    @Test
    void h72RegressionCreatesExactlyOneOpenRecommendationWithNeutralNonCausalProvenance() {
        service.reconcileRevision(revision.getId());
        assertThat(store.recommendations).hasSize(1);
        RecommendationRecord r = store.recommendations.values().iterator().next();
        assertThat(r.status()).isEqualTo(RecommendationStatus.OPEN);
        assertThat(r.window()).isEqualTo(Window.H72);
        assertThat(r.baselineValue()).isEqualByComparingTo("100");
        assertThat(r.postValue()).isEqualByComparingTo("80");
        assertThat(r.relativeDifferencePercent()).isEqualByComparingTo("-20");
        assertThat(r.reason()).startsWith("Material adverse post-change difference observed");
        assertThat(r.limitations()).contains(PostChangeSafetyModels.NON_CAUSAL_DISCLAIMER);
        assertThat(r.reason().toLowerCase()).doesNotContain("bad persona").doesNotContain("caused");
        assertThat(r.executionOrigin()).isEqualTo(ExecutionOrigin.HUMAN_APPLY);
    }

    @Test
    void stableEvidenceNeverCreatesARecommendation() {
        store.cohort = w -> cohort(10, "98");
        service.reconcileRevision(revision.getId());
        assertThat(store.recommendations).isEmpty();
        assertThat(evaluationsOf(Window.H72).get(0).status()).isEqualTo(EvaluationStatus.READY_STABLE);
    }

    @Test
    void materialImprovementNeverCreatesARecommendation() {
        store.cohort = w -> cohort(10, "180");
        service.reconcileRevision(revision.getId());
        assertThat(store.recommendations).isEmpty();
    }

    @Test
    void immatureEvidenceIsTooYoungAndInsufficientSampleIsRecordedAsSuch() {
        store.cohort = w -> List.of(new CohortRow(new UUID(0, 1), null, null, null, false, null, null));
        service.reconcileRevision(revision.getId());
        assertThat(evaluationsOf(Window.H72).get(0).status()).isEqualTo(EvaluationStatus.TOO_YOUNG);
        store.cohort = w -> cohort(3, "10");
        service.reconcileRevision(revision.getId());
        assertThat(evaluationsOf(Window.H72).get(1).status()).isEqualTo(EvaluationStatus.INSUFFICIENT_SAMPLE);
        assertThat(store.recommendations).isEmpty();
    }

    @Test
    void providerMismatchIsNotComparableAndCreatesNoRecommendation() {
        store.cohort = w -> {
            List<CohortRow> rows = new ArrayList<>();
            for (int i = 0; i < 10; i++) rows.add(new CohortRow(new UUID(0, i), new UUID(1, i), APPLIED_AT, "INSTAGRAM", true, new UUID(2, i), new BigDecimal("10")));
            return rows;
        };
        service.reconcileRevision(revision.getId());
        assertThat(evaluationsOf(Window.H72).get(0).status()).isEqualTo(EvaluationStatus.NOT_COMPARABLE);
        assertThat(store.recommendations).isEmpty();
    }

    // ---- revisions / dedupe ----

    @Test
    void identicalEvidenceIsDeduplicatedAndChangedEvidenceCreatesTheNextEvaluationRevision() {
        service.reconcileRevision(revision.getId());
        service.reconcileRevision(revision.getId());
        assertThat(store.evaluations).hasSize(3);
        store.cohort = w -> cohort(11, "80");
        service.reconcileRevision(revision.getId());
        assertThat(evaluationsOf(Window.H72)).hasSize(2);
        assertThat(evaluationsOf(Window.H72).get(1).evaluationRevision()).isEqualTo(2);
        assertThat(evaluationsOf(Window.H72).get(0).postSample()).as("history is immutable").isEqualTo(10);
    }

    @Test
    void repeatedRegressionEvaluationsKeepASingleActionableRecommendation() {
        for (int n = 10; n < 15; n++) {
            int size = n;
            store.cohort = w -> cohort(size, "80");
            service.reconcileRevision(revision.getId());
        }
        assertThat(store.recommendations.values().stream().filter(r -> r.status().actionable())).hasSize(1);
        assertThat(store.recommendations).hasSize(1);
    }

    @Test
    void dismissedEvidenceIsSuppressedIncludingTheOtherWindowsAlreadySeenEvidence() {
        service.reconcileRevision(revision.getId());
        RecommendationRecord h72 = store.recommendations.values().iterator().next();
        clock.now = clock.now.plusSeconds(3600);
        store.updateRecommendation(h72.id(), RecommendationStatus.DISMISSED, clock.now, UUID.randomUUID(), null);
        clock.now = clock.now.plusSeconds(3600);
        // same evidence: the H72 window is already recommended, and D7's evaluation predates the dismissal
        service.reconcileRevision(revision.getId());
        assertThat(store.recommendations).hasSize(1);
        assertThat(store.recommendations.values().iterator().next().status()).isEqualTo(RecommendationStatus.DISMISSED);
    }

    @Test
    void materiallyNewRegressionEvidenceAfterADismissalMayEscalateOnceThroughTheOtherDecisionWindow() {
        service.reconcileRevision(revision.getId());
        RecommendationRecord h72 = store.recommendations.values().iterator().next();
        clock.now = clock.now.plusSeconds(3600);
        store.updateRecommendation(h72.id(), RecommendationStatus.DISMISSED, clock.now, UUID.randomUUID(), null);
        clock.now = clock.now.plusSeconds(3600);
        store.cohort = w -> cohort(14, "70"); // new evidence (more mature publications), evaluated after the dismissal
        service.reconcileRevision(revision.getId());
        assertThat(store.recommendations.values().stream().filter(r -> r.window() == Window.H72)).hasSize(1);
        assertThat(store.recommendations.values().stream().filter(r -> r.window() == Window.D7)).hasSize(1);
        service.reconcileRevision(revision.getId());
        assertThat(store.recommendations).hasSize(2);
    }

    @Test
    void monitoringCompletesAtTheBoundedHorizonAndNeverRunsForever() {
        clock.now = APPLIED_AT.plus(Duration.ofDays(16));
        service.reconcileRevision(revision.getId());
        MonitorRecord m = store.monitors.values().iterator().next();
        assertThat(m.status()).isEqualTo(MonitorStatus.COMPLETED);
        assertThat(m.completedReason()).isEqualTo(CompletedReason.HORIZON_REACHED);
    }

    // ---- explicit endpoint ----

    @Test
    void explicitEvaluationValidatesWindowWorkspaceAndRevisionType() {
        AuthenticatedUser user = new AuthenticatedUser(owner);
        assertThatThrownBy(() -> service.evaluateExplicit(user, robotId, revision.getId(), "LATEST"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("UNSUPPORTED_OBSERVATION_WINDOW");
        assertThatThrownBy(() -> service.evaluateExplicit(user, robotId, UUID.randomUUID(), "H72"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        RobotConfigurationRevision rollback = revision(2, ChangeType.ROLLBACK, personaB, personaA, ExecutionOrigin.HUMAN_ROLLBACK, null, APPLIED_AT);
        stubRevision(rollback);
        assertThatThrownBy(() -> service.evaluateExplicit(user, robotId, rollback.getId(), "H72"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("REVISION_NOT_MONITORABLE");
        EvaluationRecord e = service.evaluateExplicit(user, robotId, revision.getId(), null);
        assertThat(e.window()).isEqualTo(Window.H72);
        assertThat(service.evaluateExplicit(user, robotId, revision.getId(), "h72").id()).as("idempotent").isEqualTo(e.id());
    }

    @Test
    void otherWorkspaceCannotReadOrEvaluateTheRevision() {
        Workspace other = new Workspace("Other", "other");
        AppUser stranger = new AppUser("s@example.test", "h", "S");
        AuthenticatedUser user = new AuthenticatedUser(stranger);
        when(auth.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(other, stranger, WorkspaceRole.OWNER));
        when(revisions.findByWorkspaceAndRobotIdAndId(other, robotId, revision.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.evaluateExplicit(user, robotId, revision.getId(), "H72")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.safety(user, robotId, revision.getId())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.evaluations(user, robotId, revision.getId(), 10)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void safetyViewShowsPendingStateBeforeAnyMonitorExists() {
        AuthenticatedUser user = new AuthenticatedUser(owner);
        RevisionSafety view = service.safety(user, robotId, revision.getId());
        assertThat(view.monitorId()).isNull();
        assertThat(view.disclaimer()).isEqualTo(PostChangeSafetyModels.NON_CAUSAL_DISCLAIMER);
    }
}
