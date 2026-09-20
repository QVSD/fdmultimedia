package com.fdmultimedia.api.experiments;

import com.fdmultimedia.api.analytics.DashboardModels.BreakdownRow;
import com.fdmultimedia.api.analytics.DashboardModels.Coverage;
import com.fdmultimedia.api.analytics.DashboardModels.MetricAggregate;
import com.fdmultimedia.api.analytics.DashboardQuery;
import com.fdmultimedia.api.analytics.DashboardQuery.Dimension;
import com.fdmultimedia.api.analytics.PublicationDashboardStore;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Descriptive-only outcome evidence (items 41-48). Never returns a
 * winner/score/significance (item 42) and never runs a second snapshot-
 * selection algorithm (item 43) — {@link PublicationDashboardStore#segments}
 * is the exact same Phase 13B machinery {@code PerformanceInsightService}
 * itself reuses, scoped here by an {@code experimentId} filter and a new
 * {@code EXPERIMENT_VARIANT} dimension so a Publication's variant membership
 * is always read from its own immutable {@code publication_attributions}
 * snapshot (item 44), never from a Robot's current, possibly since-changed
 * {@code experimentId}.
 */
@Service
public class ExperimentOutcomeService {

    private static final String DISCLAIMER =
            "Experiment outcomes are descriptive in Phase 14A. No statistical winner or causal conclusion is calculated yet.";

    private static final String FUNNEL_SQL = """
            SELECT ea.experiment_variant_id AS variant_id,
                   COUNT(*) AS assigned_runs,
                   COUNT(*) FILTER (WHERE rr.status = 'FAILED') AS failed_runs,
                   COUNT(*) FILTER (WHERE rr.content_draft_id IS NOT NULL) AS runs_with_draft
            FROM experiment_assignments ea
            JOIN robot_runs rr ON rr.id = ea.robot_run_id
            WHERE ea.experiment_id = :experimentId
            GROUP BY ea.experiment_variant_id
            """;

    private final AuthService authService;
    private final ExperimentRepository experiments;
    private final ExperimentVariantRepository variants;
    private final PublicationDashboardStore dashboardStore;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ExperimentOutcomeService(
            AuthService authService,
            ExperimentRepository experiments,
            ExperimentVariantRepository variants,
            PublicationDashboardStore dashboardStore,
            NamedParameterJdbcTemplate jdbc,
            Clock clock) {
        this.authService = authService;
        this.experiments = experiments;
        this.variants = variants;
        this.dashboardStore = dashboardStore;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ExperimentOutcome outcomes(AuthenticatedUser principal, UUID experimentId) {
        Workspace workspace = authService.currentMembershipFor(principal).getWorkspace();
        Experiment experiment = experiments.findByWorkspaceAndId(workspace, experimentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found"));
        List<ExperimentVariant> variantRows = variants.findByExperimentOrderByVariantKeyAsc(experiment);

        Map<UUID, long[]> funnel = queryFunnel(experimentId);
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate from = experiment.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        DashboardQuery query = new DashboardQuery(workspace.getId(), from, today, experiment.getTargetObservationWindow(),
                null, null, null, null, null, null, experimentId);
        List<String> keys = variantRows.stream().map(v -> v.getId().toString()).toList();
        List<BreakdownRow> rows = dashboardStore.segments(query, Dimension.EXPERIMENT_VARIANT, keys, now);
        Map<String, BreakdownRow> byKey = rows.stream().collect(Collectors.toMap(BreakdownRow::key, r -> r));

        List<ExperimentOutcomeVariant> outcomeVariants = variantRows.stream()
                .map(v -> buildOutcome(v, byKey.get(v.getId().toString()), funnel.get(v.getId()), experiment.getPrimaryMetric()))
                .toList();
        List<String> notices = maturityNotices(outcomeVariants);
        return new ExperimentOutcome(experiment.getId(), experiment.getTargetObservationWindow().name(),
                experiment.getPrimaryMetric().name(), DISCLAIMER, notices, outcomeVariants);
    }

    private ExperimentOutcomeVariant buildOutcome(ExperimentVariant variant, BreakdownRow row, long[] funnelRow, DashboardQuery.Metric metric) {
        long assignedRuns = funnelRow == null ? 0 : funnelRow[0];
        long failedRuns = funnelRow == null ? 0 : funnelRow[1];
        long runsWithDraft = funnelRow == null ? 0 : funnelRow[2];
        if (row == null) {
            return new ExperimentOutcomeVariant(variant.getId(), variant.getVariantKey(), variant.getLabel(),
                    assignedRuns, failedRuns, runsWithDraft, 0, 0, 0, 0, null, null, null);
        }
        Coverage coverage = row.coverage();
        MetricAggregate aggregate = row.metrics().get(metric);
        BigDecimal ratio = coverage.eligibleByAgeCount() > 0
                ? BigDecimal.valueOf(aggregate.sampleCount()).divide(BigDecimal.valueOf(coverage.eligibleByAgeCount()), 4, RoundingMode.HALF_UP)
                : null;
        return new ExperimentOutcomeVariant(variant.getId(), variant.getVariantKey(), variant.getLabel(),
                assignedRuns, failedRuns, runsWithDraft, coverage.publicationCount(), coverage.eligibleByAgeCount(),
                coverage.analyticsPublicationCount(), aggregate.sampleCount(), ratio, aggregate.average(), aggregate.median());
    }

    private List<String> maturityNotices(List<ExperimentOutcomeVariant> outcomeVariants) {
        List<String> notices = new ArrayList<>();
        boolean anyPublished = outcomeVariants.stream().anyMatch(v -> v.publishedCount() > 0);
        if (!anyPublished) {
            notices.add("No assigned RobotRun has produced a published Publication yet.");
            return notices;
        }
        boolean anyImmature = outcomeVariants.stream()
                .anyMatch(v -> v.publishedCount() > v.eligibleByAgeCount());
        if (anyImmature) {
            notices.add("Some experiment publications have not yet reached the configured observation window.");
        }
        return notices;
    }

    private Map<UUID, long[]> queryFunnel(UUID experimentId) {
        MapSqlParameterSource params = new MapSqlParameterSource("experimentId", experimentId);
        return jdbc.query(FUNNEL_SQL, params, rs -> {
            Map<UUID, long[]> result = new java.util.HashMap<>();
            while (rs.next()) {
                result.put(rs.getObject("variant_id", UUID.class),
                        new long[] {rs.getLong("assigned_runs"), rs.getLong("failed_runs"), rs.getLong("runs_with_draft")});
            }
            return result;
        });
    }
}
