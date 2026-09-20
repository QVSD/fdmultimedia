package com.fdmultimedia.api.experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.analytics.DashboardQuery;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ExperimentAnalysisServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final ExperimentRepository experiments = mock(ExperimentRepository.class);
    private final ExperimentVariantRepository variants = mock(ExperimentVariantRepository.class);
    private final ExperimentAnalysisStore store = mock(ExperimentAnalysisStore.class);
    private final ExperimentAnalysisProperties properties = new ExperimentAnalysisProperties();
    private final ExperimentAnalysisService service = new ExperimentAnalysisService(
            authService, experiments, variants, store, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;
    private Experiment experiment;
    private ExperimentVariant variantA;
    private ExperimentVariant variantB;
    private final List<AssignmentObservationRow> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        experiment = new Experiment(workspace, "Persona test", null, "hypothesis", ExperimentFactor.PERSONA,
                DashboardQuery.Window.H72, DashboardQuery.Metric.VIEWS, owner, NOW.minusSeconds(3600 * 24));
        experiment.activate(NOW.minusSeconds(3600 * 12));
        variantA = new ExperimentVariant(experiment, ExperimentVariantKey.A, "Variant A", UUID.randomUUID(), NOW);
        variantB = new ExperimentVariant(experiment, ExperimentVariantKey.B, "Variant B", UUID.randomUUID(), NOW);
        when(experiments.findByWorkspaceAndId(workspace, experiment.getId())).thenReturn(Optional.of(experiment));
        when(variants.findByExperimentOrderByVariantKeyAsc(experiment)).thenReturn(List.of(variantA, variantB));
        when(store.countAssignments(experiment.getId())).thenAnswer(inv -> (long) rows.size());
        when(store.fetch(eq(experiment.getId()), any(), any(), any())).thenAnswer(inv -> List.copyOf(rows));
    }

    private AssignmentObservationRow row(UUID variantId, String runStatus, UUID publicationId, Boolean deviation,
            boolean eligible, boolean tooYoung, Double metricValue) {
        return new AssignmentObservationRow(UUID.randomUUID(), variantId, runStatus, publicationId, eligible ? NOW.minusSeconds(400_000) : null,
                metricValue == null ? null : "TEST", deviation, eligible, tooYoung,
                metricValue == null ? null : UUID.randomUUID(), metricValue == null ? null : BigDecimal.valueOf(metricValue));
    }

    private void addObservedRows(UUID variantId, int count, double startValue) {
        for (int i = 0; i < count; i++) {
            rows.add(row(variantId, "SUCCEEDED", UUID.randomUUID(), false, true, false, startValue + i));
        }
    }

    // ---- workspace isolation / not found ----

    @Test
    void throwsNotFoundForAForeignWorkspaceExperiment() {
        when(experiments.findByWorkspaceAndId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(user, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- analysis version / metric-window lock ----

    @Test
    void responseCarriesTheAnalysisVersionAndTheExperimentsFrozenMetricAndWindow() {
        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        assertThat(response.analysisVersion()).isEqualTo("EXPERIMENT_ANALYSIS_V1");
        assertThat(response.targetObservationWindow()).isEqualTo("H72");
        assertThat(response.primaryMetric()).isEqualTo("VIEWS");
        assertThat(response.confidenceLevel()).isEqualByComparingTo(new BigDecimal("0.95"));
    }

    // ---- NO_OBSERVATIONS ----

    @Test
    void zeroAssignmentsProducesNoObservationsForBothPopulations() {
        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        assertThat(response.assignedObserved().status()).isEqualTo(AnalysisStatus.NO_OBSERVATIONS);
        assertThat(response.perProtocolObserved().status()).isEqualTo(AnalysisStatus.NO_OBSERVATIONS);
        assertThat(response.assignedObserved().variantA().assignmentCount()).isZero();
    }

    // ---- funnel: failed/too-young never fabricated as zero engagement ----

    @Test
    void failedRunsAreCountedInTheFunnelButNeverContributeAFabricatedZeroMetric() {
        rows.add(row(variantA.getId(), "FAILED", null, null, false, false, null));
        addObservedRows(variantA.getId(), 5, 10.0);
        addObservedRows(variantB.getId(), 5, 10.0);

        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        ExperimentVariantAnalysis a = response.assignedObserved().variantA();
        assertThat(a.assignmentCount()).isEqualTo(6);
        assertThat(a.failedRunCount()).isEqualTo(1);
        assertThat(a.metricSampleCount()).isEqualTo(5);
        assertThat(a.min()).isNotNull(); // the failed run never drags the mean toward zero
    }

    @Test
    void tooYoungAssignmentsAreVisibleAndNeverTreatedAsPoorOutcomes() {
        rows.add(row(variantA.getId(), "SUCCEEDED", UUID.randomUUID(), false, false, true, null));
        addObservedRows(variantA.getId(), 5, 10.0);
        addObservedRows(variantB.getId(), 5, 10.0);

        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        ExperimentVariantAnalysis a = response.assignedObserved().variantA();
        assertThat(a.tooYoungCount()).isEqualTo(1);
        assertThat(a.metricSampleCount()).isEqualTo(5);
        assertThat(response.limitations()).anyMatch(l -> l.contains("have not yet reached the 72-hour observation window"));
    }

    // ---- protocol deviation: differs between populations (item 49/79) ----

    @Test
    void protocolDeviationIsIncludedInAssignedObservedButExcludedFromPerProtocol() {
        addObservedRows(variantA.getId(), 5, 10.0);
        // One additional deviated observation in Variant A.
        rows.add(row(variantA.getId(), "SUCCEEDED", UUID.randomUUID(), true, true, false, 999.0));
        addObservedRows(variantB.getId(), 5, 10.0);

        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        ExperimentVariantAnalysis assignedA = response.assignedObserved().variantA();
        ExperimentVariantAnalysis perProtocolA = response.perProtocolObserved().variantA();
        assertThat(assignedA.metricSampleCount()).isEqualTo(6);
        assertThat(perProtocolA.metricSampleCount()).isEqualTo(5);
        assertThat(assignedA.protocolDeviationCount()).isEqualTo(1);
        assertThat(perProtocolA.protocolDeviationCount()).isEqualTo(1);
        assertThat(response.limitations()).anyMatch(l -> l.contains("protocol deviation") && l.contains("excluded from the per-protocol analysis"));
    }

    // ---- insufficient sample / ready ----

    @Test
    void belowConfiguredMinimumProducesInsufficientSampleButKeepsDescriptiveStats() {
        addObservedRows(variantA.getId(), 2, 10.0);
        addObservedRows(variantB.getId(), 2, 20.0);

        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        assertThat(response.assignedObserved().status()).isEqualTo(AnalysisStatus.INSUFFICIENT_SAMPLE);
        assertThat(response.assignedObserved().variantA().mean()).isNotNull();
        assertThat(response.assignedObserved().effect().standardError()).isNull();
        assertThat(response.assignedObserved().effect().confidenceIntervalLower()).isNull();
        assertThat(response.assignedObserved().effect().pValue()).isNull();
        assertThat(response.limitations()).anyMatch(l -> l.contains("More observed outcomes are required"));
    }

    @Test
    void atOrAboveConfiguredMinimumWithRealVarianceIsReady() {
        addObservedRows(variantA.getId(), 5, 10.0);
        addObservedRows(variantB.getId(), 5, 5.0);

        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        assertThat(response.assignedObserved().status()).isEqualTo(AnalysisStatus.READY);
        assertThat(response.assignedObserved().effect().standardError()).isNotNull();
        assertThat(response.assignedObserved().effect().confidenceIntervalLower()).isNotNull();
        assertThat(response.assignedObserved().effect().pValue()).isNotNull();
    }

    @Test
    void zeroVarianceInBothArmsIsInsufficientVarianceNotANaNOrFabricatedPValue() {
        for (int i = 0; i < 5; i++) {
            rows.add(row(variantA.getId(), "SUCCEEDED", UUID.randomUUID(), false, true, false, 10.0));
            rows.add(row(variantB.getId(), "SUCCEEDED", UUID.randomUUID(), false, true, false, 20.0));
        }

        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        assertThat(response.assignedObserved().status()).isEqualTo(AnalysisStatus.INSUFFICIENT_VARIANCE);
        assertThat(response.assignedObserved().effect().pValue()).isNull();
        assertThat(response.assignedObserved().effect().confidenceIntervalLower()).isNull();
        assertThat(response.assignedObserved().variantA().standardDeviation()).isEqualByComparingTo(BigDecimal.ZERO.setScale(4));
    }

    // ---- mixed providers ----

    @Test
    void mixedProvidersBlocksInferenceButKeepsDescriptiveStats() {
        for (int i = 0; i < 5; i++) {
            rows.add(new AssignmentObservationRow(UUID.randomUUID(), variantA.getId(), "SUCCEEDED", UUID.randomUUID(),
                    NOW.minusSeconds(400_000), i % 2 == 0 ? "TEST" : "INSTAGRAM", false, true, false, UUID.randomUUID(), BigDecimal.valueOf(10 + i)));
        }
        addObservedRows(variantB.getId(), 5, 5.0);

        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        assertThat(response.assignedObserved().status()).isEqualTo(AnalysisStatus.MIXED_PROVIDERS);
        assertThat(response.assignedObserved().variantA().mean()).isNotNull();
        assertThat(response.assignedObserved().effect().pValue()).isNull();
        assertThat(response.limitations()).anyMatch(l -> l.contains("span more than one analytics provider"));
    }

    // ---- TEST provider warning ----

    @Test
    void testProviderObservationsTriggerTheDeterministicDataLimitation() {
        addObservedRows(variantA.getId(), 5, 10.0);
        addObservedRows(variantB.getId(), 5, 5.0);

        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        assertThat(response.limitations()).anyMatch(l -> l.contains("TEST analytics are deterministic development data"));
    }

    // ---- active peeking warning (item 40/70) ----

    @Test
    void activeExperimentCarriesTheInterimAnalysisWarning() {
        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());
        assertThat(response.experimentStatus()).isEqualTo(ExperimentStatus.ACTIVE);
        assertThat(response.activeExperimentWarning()).contains("Repeatedly checking interim results");
    }

    @Test
    void pausedExperimentHasNoActivePeekingWarning() {
        experiment.pause(NOW);
        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());
        assertThat(response.activeExperimentWarning()).isNull();
    }

    @Test
    void completedExperimentAnalysisStillWorksAndHasNoActiveWarning() {
        experiment.pause(NOW);
        experiment.complete(NOW);
        addObservedRows(variantA.getId(), 5, 10.0);
        addObservedRows(variantB.getId(), 5, 5.0);

        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        assertThat(response.experimentStatus()).isEqualTo(ExperimentStatus.COMPLETED);
        assertThat(response.activeExperimentWarning()).isNull();
        assertThat(response.assignedObserved().status()).isEqualTo(AnalysisStatus.READY);
    }

    @Test
    void cancelledExperimentAnalysisRemainsReadableWhenDataExists() {
        experiment.cancel(NOW);
        addObservedRows(variantA.getId(), 5, 10.0);
        addObservedRows(variantB.getId(), 5, 5.0);

        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        assertThat(response.experimentStatus()).isEqualTo(ExperimentStatus.CANCELLED);
        assertThat(response.assignedObserved().status()).isEqualTo(AnalysisStatus.READY);
    }

    // ---- attrition warning ----

    @Test
    void substantiallyDifferentOutcomeCoverageBetweenVariantsShowsANeutralAttritionWarning() {
        // Variant A: 10 assigned, all eligible and observed (100% eligible coverage).
        addObservedRows(variantA.getId(), 10, 10.0);
        // Variant B: 10 assigned, only 3 eligible+observed, 7 too young (30% eligible coverage) -> 70pp gap.
        addObservedRows(variantB.getId(), 3, 5.0);
        for (int i = 0; i < 7; i++) {
            rows.add(row(variantB.getId(), "SUCCEEDED", UUID.randomUUID(), false, false, true, null));
        }

        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        assertThat(response.limitations()).anyMatch(l -> l.equals("Outcome availability differs between variants."));
    }

    // ---- max assignment bound (item 58/59) ----

    @Test
    void exceedingTheMaxAnalysisBoundReturnsAControlledConflictNeverASilentTruncation() {
        when(store.countAssignments(experiment.getId())).thenReturn(ExperimentAnalysisStore.MAX_ANALYSIS_ASSIGNMENTS + 1);

        assertThatThrownBy(() -> service.analyze(user, experiment.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- no-mutation (item 61) ----

    @Test
    void analysisNeverCallsAnySaveOrUpdateMethodOnAnyRepository() {
        addObservedRows(variantA.getId(), 5, 10.0);
        addObservedRows(variantB.getId(), 5, 5.0);

        service.analyze(user, experiment.getId());

        org.mockito.Mockito.verify(experiments, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verify(variants, org.mockito.Mockito.never()).save(any());
    }

    // ---- winner-language guard (item 86) ----

    @Test
    void noResponseTextEverUsesWinnerOrDecisionLanguage() {
        addObservedRows(variantA.getId(), 5, 10.0);
        addObservedRows(variantB.getId(), 5, 5.0);
        rows.add(row(variantA.getId(), "SUCCEEDED", UUID.randomUUID(), true, true, false, 50.0));

        ExperimentAnalysisResponse response = service.analyze(user, experiment.getId());

        List<String> allText = new ArrayList<>(response.limitations());
        if (response.activeExperimentWarning() != null) {
            allText.add(response.activeExperimentWarning());
        }
        String combined = String.join(" ", allText).toLowerCase();
        assertThat(combined).doesNotContain("winner", "loser", "winning", "best variant", "recommended variant", "deploy a", "deploy b");
    }
}
