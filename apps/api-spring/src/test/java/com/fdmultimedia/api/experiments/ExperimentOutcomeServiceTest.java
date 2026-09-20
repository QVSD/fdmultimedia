package com.fdmultimedia.api.experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.analytics.DashboardModels.BreakdownRow;
import com.fdmultimedia.api.analytics.DashboardModels.Coverage;
import com.fdmultimedia.api.analytics.DashboardModels.MetricAggregate;
import com.fdmultimedia.api.analytics.DashboardQuery;
import com.fdmultimedia.api.analytics.PublicationDashboardStore;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** Item 42 (no winner) and item 44/45 (funnel from immutable attribution) are the focus here. */
class ExperimentOutcomeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final ExperimentRepository experiments = mock(ExperimentRepository.class);
    private final ExperimentVariantRepository variants = mock(ExperimentVariantRepository.class);
    private final PublicationDashboardStore dashboardStore = mock(PublicationDashboardStore.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final ExperimentOutcomeService service = new ExperimentOutcomeService(
            authService, experiments, variants, dashboardStore, jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;
    private Experiment experiment;
    private ExperimentVariant variantA;
    private ExperimentVariant variantB;

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
    }

    @SuppressWarnings("unchecked")
    private void stubFunnel(Map<UUID, long[]> funnel) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(ResultSetExtractor.class))).thenReturn(funnel);
    }

    private BreakdownRow row(String key, long publicationCount, long eligible, long analyticsCount, long sampleCount, BigDecimal average, BigDecimal median) {
        Coverage coverage = new Coverage(publicationCount, analyticsCount, eligible, 0, 0);
        Map<DashboardQuery.Metric, MetricAggregate> metrics = new EnumMap<>(DashboardQuery.Metric.class);
        for (DashboardQuery.Metric metric : DashboardQuery.Metric.values()) {
            metrics.put(metric, new MetricAggregate(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0));
        }
        metrics.put(DashboardQuery.Metric.VIEWS, new MetricAggregate(BigDecimal.ZERO, average, median, sampleCount));
        return new BreakdownRow(key, "Label " + key, coverage, metrics);
    }

    @Test
    void outcomeNeverExposesAWinnerOrSignificanceField() {
        stubFunnel(Map.of(variantA.getId(), new long[] {5, 0, 5}, variantB.getId(), new long[] {5, 0, 5}));
        when(dashboardStore.segments(any(), eq(DashboardQuery.Dimension.EXPERIMENT_VARIANT), any(), any())).thenReturn(List.of());

        ExperimentOutcome outcome = service.outcomes(user, experiment.getId());

        assertThat(outcome.disclaimer()).contains("descriptive");
        assertThat(outcome.getClass().getRecordComponents())
                .noneMatch(c -> c.getName().toLowerCase().contains("winner") || c.getName().toLowerCase().contains("significan"));
    }

    @Test
    void outcomeExposesDescriptiveEvidencePerVariantWithFunnelCounts() {
        stubFunnel(Map.of(
                variantA.getId(), new long[] {8, 1, 6},
                variantB.getId(), new long[] {8, 0, 7}));
        when(dashboardStore.segments(any(), eq(DashboardQuery.Dimension.EXPERIMENT_VARIANT), any(), any())).thenReturn(List.of(
                row(variantA.getId().toString(), 6, 6, 6, 6, BigDecimal.valueOf(100), BigDecimal.valueOf(90)),
                row(variantB.getId().toString(), 7, 7, 7, 7, BigDecimal.valueOf(120), BigDecimal.valueOf(110))));

        ExperimentOutcome outcome = service.outcomes(user, experiment.getId());

        assertThat(outcome.variants()).hasSize(2);
        ExperimentOutcomeVariant a = outcome.variants().get(0);
        assertThat(a.variantKey()).isEqualTo(ExperimentVariantKey.A);
        assertThat(a.assignedRuns()).isEqualTo(8);
        assertThat(a.failedRuns()).isEqualTo(1);
        assertThat(a.runsWithDraft()).isEqualTo(6);
        assertThat(a.publishedCount()).isEqualTo(6);
        assertThat(a.median()).isEqualByComparingTo(BigDecimal.valueOf(90));
        ExperimentOutcomeVariant b = outcome.variants().get(1);
        assertThat(b.median()).isEqualByComparingTo(BigDecimal.valueOf(110));
    }

    @Test
    void missingSegmentDataIsTreatedAsZeroNotAnError() {
        stubFunnel(Map.of());
        when(dashboardStore.segments(any(), eq(DashboardQuery.Dimension.EXPERIMENT_VARIANT), any(), any())).thenReturn(List.of());

        ExperimentOutcome outcome = service.outcomes(user, experiment.getId());

        assertThat(outcome.variants()).allMatch(v -> v.assignedRuns() == 0 && v.publishedCount() == 0 && v.median() == null);
        assertThat(outcome.notices()).contains("No assigned RobotRun has produced a published Publication yet.");
    }
}
