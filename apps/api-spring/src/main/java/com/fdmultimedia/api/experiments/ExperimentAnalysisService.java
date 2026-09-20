package com.fdmultimedia.api.experiments;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Statistical evidence, never a decision (item 1/25): this class computes
 * Welch's t-test evidence over the exact same canonical per-assignment
 * dataset both populations share (item 55/56) and never returns a
 * winner/recommendation. Read-only throughout (item 61) — no method here
 * ever calls a repository save/update.
 *
 * <p>The Experiment's own frozen {@code targetObservationWindow}/{@code
 * primaryMetric} are always used (item 5) — this endpoint accepts no
 * metric/window override, so a caller can never substitute another metric
 * and present it as "the" Experiment's primary analysis.
 */
@Service
public class ExperimentAnalysisService {

    public static final String ANALYSIS_VERSION = "EXPERIMENT_ANALYSIS_V1";

    private static final BigDecimal CONFIDENCE_LEVEL = new BigDecimal("0.95");
    private static final int STAT_SCALE = 4;
    private static final int PVALUE_SCALE = 6;
    private static final BigDecimal ATTRITION_WARNING_THRESHOLD_PERCENT = BigDecimal.valueOf(20);
    private static final String ACTIVE_WARNING =
            "This experiment is still assigning runs. Repeatedly checking interim results and stopping based on them can bias inference.";
    private static final String STANDING_DISCLAIMER =
            "This analysis describes the observed association between assigned variants in this sample "
                    + "and does not automatically identify a variant to deploy.";
    private static final String TEST_PROVIDER_WARNING =
            "TEST analytics are deterministic development data and do not represent real audience behavior.";

    private final AuthService authService;
    private final ExperimentRepository experiments;
    private final ExperimentVariantRepository variants;
    private final ExperimentAnalysisStore store;
    private final ExperimentAnalysisProperties properties;
    private final Clock clock;

    public ExperimentAnalysisService(
            AuthService authService,
            ExperimentRepository experiments,
            ExperimentVariantRepository variants,
            ExperimentAnalysisStore store,
            ExperimentAnalysisProperties properties,
            Clock clock) {
        this.authService = authService;
        this.experiments = experiments;
        this.variants = variants;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ExperimentAnalysisResponse analyze(AuthenticatedUser principal, UUID experimentId) {
        Workspace workspace = authService.currentMembershipFor(principal).getWorkspace();
        Experiment experiment = experiments.findByWorkspaceAndId(workspace, experimentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found"));
        List<ExperimentVariant> variantRows = variants.findByExperimentOrderByVariantKeyAsc(experiment);
        if (variantRows.size() != 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EXPERIMENT_VARIANTS_UNAVAILABLE");
        }
        ExperimentVariant variantA = variantRows.stream().filter(v -> v.getVariantKey() == ExperimentVariantKey.A).findFirst().orElseThrow();
        ExperimentVariant variantB = variantRows.stream().filter(v -> v.getVariantKey() == ExperimentVariantKey.B).findFirst().orElseThrow();

        // Item 58: a controlled bound checked BEFORE running the row query — never a silent truncation (item 59).
        long assignmentCount = store.countAssignments(experimentId);
        if (assignmentCount > ExperimentAnalysisStore.MAX_ANALYSIS_ASSIGNMENTS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EXPERIMENT_ANALYSIS_TOO_LARGE");
        }

        Instant now = Instant.now(clock);
        List<AssignmentObservationRow> rows = assignmentCount == 0 ? List.of()
                : store.fetch(experimentId, experiment.getTargetObservationWindow(), experiment.getPrimaryMetric(), now);
        List<AssignmentObservationRow> rowsA = rows.stream().filter(r -> variantA.getId().equals(r.variantId())).toList();
        List<AssignmentObservationRow> rowsB = rows.stream().filter(r -> variantB.getId().equals(r.variantId())).toList();

        ExperimentPopulationAnalysis assignedObserved =
                buildPopulation(AnalysisPopulation.ASSIGNED_OBSERVED, variantA, variantB, rowsA, rowsB, false);
        ExperimentPopulationAnalysis perProtocolObserved =
                buildPopulation(AnalysisPopulation.PER_PROTOCOL_OBSERVED, variantA, variantB, rowsA, rowsB, true);

        Set<String> observedProviders = new HashSet<>();
        rows.stream().filter(r -> r.metricValue() != null).map(AssignmentObservationRow::provider)
                .filter(Objects::nonNull).forEach(observedProviders::add);
        List<String> limitations = buildLimitations(experiment, assignedObserved, perProtocolObserved, observedProviders);
        String activeWarning = experiment.getStatus() == ExperimentStatus.ACTIVE ? ACTIVE_WARNING : null;

        return new ExperimentAnalysisResponse(ANALYSIS_VERSION, experiment.getId(), experiment.getName(), experiment.getStatus(),
                experiment.getFactor(), experiment.getTargetObservationWindow().name(), experiment.getPrimaryMetric().name(),
                CONFIDENCE_LEVEL, activeWarning, limitations, assignedObserved, perProtocolObserved);
    }

    // ---- population/variant aggregation ----

    private record VariantResult(
            ExperimentVariantAnalysis dto, WelchStatistics.DescriptiveStats stats, Set<String> providers) {
    }

    private ExperimentPopulationAnalysis buildPopulation(AnalysisPopulation population, ExperimentVariant variantA,
            ExperimentVariant variantB, List<AssignmentObservationRow> rowsA, List<AssignmentObservationRow> rowsB,
            boolean perProtocolOnly) {
        VariantResult resultA = summarizeVariant(variantA, rowsA, perProtocolOnly);
        VariantResult resultB = summarizeVariant(variantB, rowsB, perProtocolOnly);

        Set<String> providers = new HashSet<>(resultA.providers());
        providers.addAll(resultB.providers());

        WelchStatistics.EffectEstimate welch = WelchStatistics.estimate(resultA.stats(), resultB.stats());

        AnalysisStatus status;
        if (resultA.dto().metricSampleCount() == 0 && resultB.dto().metricSampleCount() == 0) {
            status = AnalysisStatus.NO_OBSERVATIONS;
        } else if (providers.size() > 1) {
            status = AnalysisStatus.MIXED_PROVIDERS;
        } else if (resultA.dto().metricSampleCount() < properties.getMinSamplePerVariant()
                || resultB.dto().metricSampleCount() < properties.getMinSamplePerVariant()) {
            status = AnalysisStatus.INSUFFICIENT_SAMPLE;
        } else if (welch.degenerateVariance()) {
            status = AnalysisStatus.INSUFFICIENT_VARIANCE;
        } else {
            status = AnalysisStatus.READY;
        }

        boolean ready = status == AnalysisStatus.READY;
        ExperimentEffectEstimate effect = new ExperimentEffectEstimate(
                round(welch.absoluteMeanDifference(), STAT_SCALE),
                round(welch.relativeMeanDifferencePercent(), STAT_SCALE),
                ready ? round(welch.standardError(), STAT_SCALE) : null,
                ready ? round(welch.degreesOfFreedom(), STAT_SCALE) : null,
                ready ? round(welch.confidenceIntervalLower(), STAT_SCALE) : null,
                ready ? round(welch.confidenceIntervalUpper(), STAT_SCALE) : null,
                ready ? welch.confidenceIntervalIncludesZero() : null,
                ready ? round(welch.pValue(), PVALUE_SCALE) : null,
                ready ? round(welch.standardizedEffectSize(), STAT_SCALE) : null);

        return new ExperimentPopulationAnalysis(population, status, resultA.dto(), resultB.dto(), effect);
    }

    private VariantResult summarizeVariant(ExperimentVariant variant, List<AssignmentObservationRow> rows, boolean perProtocolOnly) {
        long assignmentCount = rows.size();
        long failedRunCount = rows.stream().filter(r -> "FAILED".equals(r.robotRunStatus())).count();
        long publishedCount = rows.stream().filter(r -> r.publicationId() != null).count();
        long eligibleByAgeCount = rows.stream().filter(AssignmentObservationRow::eligibleByAge).count();
        long tooYoungCount = rows.stream().filter(AssignmentObservationRow::tooYoung).count();
        long snapshotCount = rows.stream().filter(r -> r.snapshotId() != null).count();
        long protocolDeviationCount = rows.stream().filter(r -> Boolean.TRUE.equals(r.protocolDeviation())).count();

        List<AssignmentObservationRow> included = rows.stream()
                .filter(r -> r.metricValue() != null)
                .filter(r -> !perProtocolOnly || !Boolean.TRUE.equals(r.protocolDeviation()))
                .toList();
        long metricSampleCount = included.size();

        List<Double> values = included.stream().map(r -> r.metricValue().doubleValue()).toList();
        WelchStatistics.DescriptiveStats stats = WelchStatistics.DescriptiveStats.of(values);
        Set<String> providers = included.stream().map(AssignmentObservationRow::provider).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());

        BigDecimal assignmentCoverage = assignmentCount > 0 ? ratio(metricSampleCount, assignmentCount) : null;
        BigDecimal eligibleCoverage = eligibleByAgeCount > 0 ? ratio(metricSampleCount, eligibleByAgeCount) : null;

        ExperimentVariantAnalysis dto = new ExperimentVariantAnalysis(variant.getVariantKey(), variant.getLabel(),
                assignmentCount, failedRunCount, publishedCount, eligibleByAgeCount, tooYoungCount, snapshotCount,
                metricSampleCount, protocolDeviationCount, assignmentCoverage, eligibleCoverage,
                round(stats.mean(), STAT_SCALE), round(stats.median(), STAT_SCALE),
                round(stats.standardDeviation(), STAT_SCALE), round(stats.min(), STAT_SCALE), round(stats.max(), STAT_SCALE));
        return new VariantResult(dto, stats, providers);
    }

    // ---- limitations (item 52: deterministic backend text, no LLM) ----

    private List<String> buildLimitations(Experiment experiment, ExperimentPopulationAnalysis assignedObserved,
            ExperimentPopulationAnalysis perProtocolObserved, Set<String> observedProviders) {
        List<String> limitations = new ArrayList<>();
        limitations.add(STANDING_DISCLAIMER);

        ExperimentVariantAnalysis a = assignedObserved.variantA();
        ExperimentVariantAnalysis b = assignedObserved.variantB();
        limitations.add("Variant A has %d observed outcome(s) and Variant B has %d observed outcome(s) in the assigned-observed population."
                .formatted(a.metricSampleCount(), b.metricSampleCount()));

        long tooYoungTotal = a.tooYoungCount() + b.tooYoungCount();
        if (tooYoungTotal > 0) {
            limitations.add("%d assignment(s) have not yet reached the %s observation window."
                    .formatted(tooYoungTotal, windowPhrase(experiment.getTargetObservationWindow())));
        }

        long deviationTotal = a.protocolDeviationCount() + b.protocolDeviationCount();
        if (deviationTotal > 0) {
            limitations.add("%d protocol deviation(s) are included in the assigned-observed population and excluded from the per-protocol analysis."
                    .formatted(deviationTotal));
        }

        if (assignedObserved.status() == AnalysisStatus.MIXED_PROVIDERS || perProtocolObserved.status() == AnalysisStatus.MIXED_PROVIDERS) {
            limitations.add("Observed outcomes span more than one analytics provider; pooling a normalized metric across providers is not performed.");
        }

        if (observedProviders.contains("TEST")) {
            limitations.add(TEST_PROVIDER_WARNING);
        }

        addSmallSampleLimitation(limitations, "assigned-observed", assignedObserved);
        addSmallSampleLimitation(limitations, "per-protocol", perProtocolObserved);

        BigDecimal attritionGap = coverageGapPercent(a, b);
        if (attritionGap != null && attritionGap.compareTo(ATTRITION_WARNING_THRESHOLD_PERCENT) >= 0) {
            limitations.add("Outcome availability differs between variants.");
        }

        return limitations;
    }

    private void addSmallSampleLimitation(List<String> limitations, String populationLabel, ExperimentPopulationAnalysis population) {
        if (population.status() != AnalysisStatus.INSUFFICIENT_SAMPLE) {
            return;
        }
        limitations.add("More observed outcomes are required for the configured inferential analysis in the %s population (nA=%d, nB=%d, minimum=%d)."
                .formatted(populationLabel, population.variantA().metricSampleCount(), population.variantB().metricSampleCount(),
                        properties.getMinSamplePerVariant()));
    }

    /**
     * Item 45/46: compares {@code assignmentOutcomeCoverage} (metricSampleCount
     * / assignmentCount), not {@code eligibleOutcomeCoverage} — attrition is
     * about the whole assigned-to-observed funnel (including too-young and
     * never-published assignments), not just snapshot-collection efficiency
     * among the subset that already matured.
     */
    private BigDecimal coverageGapPercent(ExperimentVariantAnalysis a, ExperimentVariantAnalysis b) {
        if (a.assignmentOutcomeCoverage() == null || b.assignmentOutcomeCoverage() == null) {
            return null;
        }
        return a.assignmentOutcomeCoverage().subtract(b.assignmentOutcomeCoverage()).abs().multiply(BigDecimal.valueOf(100));
    }

    private static String windowPhrase(com.fdmultimedia.api.analytics.DashboardQuery.Window window) {
        return switch (window) {
            case H24 -> "24-hour";
            case H72 -> "72-hour";
            case D7 -> "7-day";
            case LATEST -> "configured";
        };
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal round(Double value, int scale) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
}
