package com.fdmultimedia.api.experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExperimentDecisionReadinessServiceTest {
    private final AuthService auth = mock(AuthService.class);
    private final ExperimentRepository repository = mock(ExperimentRepository.class);
    private final ExperimentAnalysisService analysis = mock(ExperimentAnalysisService.class);
    private final ExperimentDecisionProperties config = new ExperimentDecisionProperties();
    private final ExperimentDecisionReadinessService service = new ExperimentDecisionReadinessService(auth, repository, analysis, config, new ExperimentAnalysisProperties());
    private final AuthenticatedUser user = mock(AuthenticatedUser.class);
    private final Workspace workspace = mock(Workspace.class);
    private final UUID id = UUID.randomUUID();
    private Experiment experiment;

    @BeforeEach
    void setup() {
        WorkspaceMembership membership = mock(WorkspaceMembership.class);
        when(auth.currentMembershipFor(user)).thenReturn(membership);
        when(membership.getWorkspace()).thenReturn(workspace);
        experiment = new Experiment(workspace, "Test", null, "hypothesis", ExperimentFactor.PERSONA,
                com.fdmultimedia.api.analytics.DashboardQuery.Window.H24,
                com.fdmultimedia.api.analytics.DashboardQuery.Metric.VIEWS, null, Instant.EPOCH);
        experiment.setDraftPracticalEffect(BigDecimal.TEN, Instant.EPOCH);
        experiment.activate(Instant.EPOCH);
        when(repository.findByWorkspaceAndId(workspace, id)).thenReturn(Optional.of(experiment));
    }

    private ExperimentVariantAnalysis arm(ExperimentVariantKey key, long n, long assignments, long tooYoung, long deviations) {
        return new ExperimentVariantAnalysis(key, key.name(), assignments, 0, n, n, tooYoung, n, n, deviations,
                assignments == 0 ? null : BigDecimal.valueOf(n).divide(BigDecimal.valueOf(assignments), 4, java.math.RoundingMode.HALF_UP),
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN);
    }

    private ExperimentDecisionReadiness.Population evaluate(AnalysisStatus status, long nA, long nB, long assignedA,
            long assignedB, long tooYoung, long deviations, BigDecimal difference, BigDecimal lower, BigDecimal upper) {
        var population = new ExperimentPopulationAnalysis(AnalysisPopulation.ASSIGNED_OBSERVED, status,
                arm(ExperimentVariantKey.A, nA, assignedA, tooYoung, deviations), arm(ExperimentVariantKey.B, nB, assignedB, 0, 0),
                new ExperimentEffectEstimate(difference, null, null, null, lower, upper, true, null, null));
        when(analysis.analyze(user, id)).thenReturn(new ExperimentAnalysisResponse("EXPERIMENT_ANALYSIS_V1", id, "Test",
                ExperimentStatus.ACTIVE, ExperimentFactor.PERSONA, "H24", "VIEWS", BigDecimal.valueOf(.95), null,
                List.of(), population, population));
        return service.read(user, id).assignedObserved();
    }

    @Test
    void readyWithIntervalIncludingZeroAndEffectBelowThreshold() {
        var result = evaluate(AnalysisStatus.READY, 5, 5, 5, 5, 0, 0, BigDecimal.valueOf(5.4), BigDecimal.valueOf(-10), BigDecimal.valueOf(20));
        assertThat(result.readinessStatus()).isEqualTo("READY_FOR_REVIEW");
        assertThat(result.practicalEffectStatus()).isEqualTo("BELOW_THRESHOLD");
        assertThat(result.intervalPracticalRelationship()).isEqualTo("OVERLAPS_PRACTICAL_REGION");
    }

    @Test
    void lowCoverageAndTooYoungBlockButPreserveEvidence() {
        var result = evaluate(AnalysisStatus.INSUFFICIENT_SAMPLE, 4, 5, 10, 5, 1, 0, BigDecimal.ONE, null, null);
        assertThat(result.readinessStatus()).isEqualTo("NOT_READY");
        assertThat(result.checks()).anyMatch(c -> c.code().equals("ASSIGNMENT_OUTCOME_COVERAGE_A") && c.status().equals("BLOCKED"));
        assertThat(result.checks()).anyMatch(c -> c.code().equals("OBSERVATION_MATURITY") && c.status().equals("BLOCKED"));
    }

    @Test
    void deviationAndAttritionWarnWithoutChoosingVariant() {
        var result = evaluate(AnalysisStatus.READY, 8, 5, 10, 5, 1, 3, BigDecimal.valueOf(-12), BigDecimal.valueOf(-20), BigDecimal.valueOf(-11));
        assertThat(result.readinessStatus()).isEqualTo("READY_FOR_REVIEW");
        assertThat(result.direction()).isEqualTo("B_HIGHER_OBSERVED");
        assertThat(result.practicalEffectStatus()).isEqualTo("MEETS_OR_EXCEEDS_THRESHOLD");
        assertThat(result.intervalPracticalRelationship()).isEqualTo("ENTIRELY_BELOW_NEGATIVE_THRESHOLD");
        assertThat(result.checks()).anyMatch(c -> c.code().equals("ATTRITION_IMBALANCE") && c.status().equals("WARN"));
        assertThat(result.checks()).anyMatch(c -> c.code().equals("PROTOCOL_DEVIATION_A") && c.status().equals("WARN"));
    }

    @Test
    void legacyThresholdIsNotInvented() {
        experiment = new Experiment(workspace, "Legacy", null, "hypothesis", ExperimentFactor.PERSONA,
                com.fdmultimedia.api.analytics.DashboardQuery.Window.H24,
                com.fdmultimedia.api.analytics.DashboardQuery.Metric.VIEWS, null, Instant.EPOCH);
        experiment.setDraftPracticalEffect(null, Instant.EPOCH);
        experiment.activate(Instant.EPOCH);
        when(repository.findByWorkspaceAndId(workspace, id)).thenReturn(Optional.of(experiment));
        var result = evaluate(AnalysisStatus.READY, 5, 5, 5, 5, 0, 0, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.valueOf(20));
        assertThat(result.practicalEffectStatus()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.intervalPracticalRelationship()).isEqualTo("UNAVAILABLE");
        assertThat(result.readinessStatus()).isEqualTo("READY_FOR_REVIEW");
    }

    @Test
    void mixedProviderWithImmatureSampleBlocksBothChecks() {
        var result = evaluate(AnalysisStatus.MIXED_PROVIDERS, 4, 5, 5, 5, 1, 0, BigDecimal.ONE, null, null);
        assertThat(result.checks()).anyMatch(c -> c.code().equals("PROVIDER_CONSISTENCY") && c.status().equals("BLOCKED"));
        assertThat(result.checks()).anyMatch(c -> c.code().equals("OBSERVATION_MATURITY") && c.status().equals("BLOCKED"));
    }

    @Test
    void intervalEntirelyAboveThresholdIsDescriptiveOnly() {
        var result = evaluate(AnalysisStatus.READY, 5, 5, 5, 5, 0, 0, BigDecimal.valueOf(14), BigDecimal.valueOf(11), BigDecimal.valueOf(20));
        assertThat(result.direction()).isEqualTo("A_HIGHER_OBSERVED");
        assertThat(result.intervalPracticalRelationship()).isEqualTo("ENTIRELY_ABOVE_POSITIVE_THRESHOLD");
        assertThat(result.readinessStatus()).isEqualTo("READY_FOR_REVIEW");
    }

    @Test
    void insufficientVarianceBlocksInferenceWithoutErasingDescriptiveDifference() {
        var result = evaluate(AnalysisStatus.INSUFFICIENT_VARIANCE, 5, 5, 5, 5, 0, 0, BigDecimal.ONE, null, null);
        assertThat(result.readinessStatus()).isEqualTo("NOT_READY");
        assertThat(result.practicalEffectStatus()).isEqualTo("BELOW_THRESHOLD");
    }

    @Test
    void evidenceFingerprintIsStableAndChangesWithThreshold() {
        var population = evaluate(AnalysisStatus.READY, 5, 5, 5, 5, 0, 0, BigDecimal.valueOf(5.4), BigDecimal.valueOf(-10), BigDecimal.valueOf(20));
        var current = service.read(user, id);
        String first = ExperimentDecisionService.fingerprint(current, population);
        assertThat(ExperimentDecisionService.fingerprint(current, population)).isEqualTo(first).hasSize(64);
        var changed = new ExperimentDecisionReadiness(current.guardrailVersion(), id, current.experimentStatus(),
                current.analysisVersion(), current.primaryMetric(), current.targetObservationWindow(), BigDecimal.valueOf(11),
                population, population, current.practicalEffectNotice(), current.intervalNotice());
        assertThat(ExperimentDecisionService.fingerprint(changed, population)).isNotEqualTo(first);
    }

    @Test
    void noAssignmentsDoNotPretendProviderOrMaturityPassed() {
        var result = evaluate(AnalysisStatus.NO_OBSERVATIONS, 0, 0, 0, 0, 0, 0, null, null, null);
        assertThat(result.readinessStatus()).isEqualTo("NOT_READY");
        assertThat(result.checks()).anyMatch(c -> c.code().equals("PROVIDER_CONSISTENCY") && c.status().equals("NOT_APPLICABLE"));
        assertThat(result.checks()).anyMatch(c -> c.code().equals("OBSERVATION_MATURITY") && c.status().equals("NOT_APPLICABLE"));
    }
}
