package com.fdmultimedia.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.analytics.DashboardModels.BreakdownRow;
import com.fdmultimedia.api.analytics.DashboardModels.Coverage;
import com.fdmultimedia.api.analytics.DashboardModels.Filters;
import com.fdmultimedia.api.analytics.DashboardModels.MetricAggregate;
import com.fdmultimedia.api.analytics.DashboardModels.Summary;
import com.fdmultimedia.api.analytics.DashboardQuery.Dimension;
import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.analytics.InsightModels.ComparisonResult;
import com.fdmultimedia.api.analytics.InsightModels.Direction;
import com.fdmultimedia.api.analytics.InsightModels.InsightsResponse;
import com.fdmultimedia.api.analytics.InsightModels.Notice;
import com.fdmultimedia.api.analytics.InsightModels.Recommendation;
import com.fdmultimedia.api.analytics.InsightModels.RecommendationType;
import com.fdmultimedia.api.analytics.InsightModels.ResultType;
import com.fdmultimedia.api.analytics.InsightModels.Statistic;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PerformanceInsightServiceTest {
    private static final String ROBOT_A = "11111111-1111-1111-1111-111111111111";
    private static final String ROBOT_B = "22222222-2222-2222-2222-222222222222";

    private final PublicationDashboardService dashboardService = mock(PublicationDashboardService.class);
    private final PublicationDashboardStore store = mock(PublicationDashboardStore.class);
    private final PerformanceInsightProperties properties = new PerformanceInsightProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);
    private final PerformanceInsightService service =
            new PerformanceInsightService(dashboardService, store, properties, clock);
    private final AuthenticatedUser user = mock(AuthenticatedUser.class);
    private final DashboardQuery query = new DashboardQuery(UUID.randomUUID(),
            LocalDate.parse("2026-08-22"), LocalDate.parse("2026-09-20"), Window.H72,
            null, null, null, null, null, null);

    @BeforeEach
    void stubDashboardQuery() {
        when(dashboardService.query(eq(user), any())).thenReturn(query);
    }

    // ---- direction / arithmetic (ORIGIN dimension: fixed MANUAL/ROBOT vocabulary) ----

    @Test
    void higherObservedWhenLeftExceedsRightBeyondThreshold() {
        stubOrigin(row("MANUAL", 20, 100, 20), row("ROBOT", 20, 80, 20));
        ComparisonResult result = compareOrigin("MANUAL", "ROBOT");
        assertThat(result.type()).isEqualTo(ResultType.DIRECTIONAL_COMPARISON);
        assertThat(result.direction()).isEqualTo(Direction.HIGHER_OBSERVED);
        assertThat(result.materialDifference()).isTrue();
        assertThat(result.absoluteDifference()).isEqualByComparingTo("20");
        assertThat(result.relativeDifferencePercent()).isEqualByComparingTo("25");
        assertThat(result.message()).doesNotContainIgnoringCase("better")
                .doesNotContainIgnoringCase("worse").doesNotContainIgnoringCase("winner")
                .doesNotContainIgnoringCase("loser").doesNotContainIgnoringCase("best");
    }

    @Test
    void lowerObservedWhenLeftBelowRightBeyondThreshold() {
        stubOrigin(row("MANUAL", 20, 80, 20), row("ROBOT", 20, 100, 20));
        ComparisonResult result = compareOrigin("MANUAL", "ROBOT");
        assertThat(result.direction()).isEqualTo(Direction.LOWER_OBSERVED);
        assertThat(result.materialDifference()).isTrue();
        assertThat(result.absoluteDifference()).isEqualByComparingTo("-20");
        assertThat(result.relativeDifferencePercent()).isEqualByComparingTo("-20");
    }

    @Test
    void similarObservedWhenDifferenceBelowMaterialThreshold() {
        // 5% difference, below the default 10% material threshold.
        stubOrigin(row("MANUAL", 20, 105, 20), row("ROBOT", 20, 100, 20));
        ComparisonResult result = compareOrigin("MANUAL", "ROBOT");
        assertThat(result.direction()).isEqualTo(Direction.SIMILAR_OBSERVED);
        assertThat(result.materialDifference()).isFalse();
        assertThat(result.recommendations()).isEmpty();
    }

    @Test
    void exactlyAtThresholdIsMaterial() {
        stubOrigin(row("MANUAL", 20, 110, 20), row("ROBOT", 20, 100, 20));
        ComparisonResult result = compareOrigin("MANUAL", "ROBOT");
        assertThat(result.materialDifference()).isTrue();
        assertThat(result.direction()).isEqualTo(Direction.HIGHER_OBSERVED);
    }

    @Test
    void zeroRightValueMakesRelativeDifferenceNullButStaysMaterialWhenLeftNonZero() {
        stubOrigin(row("MANUAL", 20, 5, 20), row("ROBOT", 20, 0, 20));
        ComparisonResult result = compareOrigin("MANUAL", "ROBOT");
        assertThat(result.relativeDifferencePercent()).isNull();
        assertThat(result.absoluteDifference()).isEqualByComparingTo("5");
        assertThat(result.materialDifference()).isTrue();
        assertThat(result.direction()).isEqualTo(Direction.HIGHER_OBSERVED);
    }

    @Test
    void bothSidesZeroIsSimilarNotMaterial() {
        stubOrigin(row("MANUAL", 20, 0, 20), row("ROBOT", 20, 0, 20));
        ComparisonResult result = compareOrigin("MANUAL", "ROBOT");
        assertThat(result.relativeDifferencePercent()).isNull();
        assertThat(result.absoluteDifference()).isEqualByComparingTo("0");
        assertThat(result.materialDifference()).isFalse();
        assertThat(result.direction()).isEqualTo(Direction.SIMILAR_OBSERVED);
    }

    @Test
    void usesMedianByDefaultAndAverageWhenRequested() {
        // Deliberately skewed: median differs materially (50 vs 40 = 25%), but the mean is
        // pulled close together by outliers (52 vs 50 = 4%) — proves the two statistics are
        // evaluated independently, not just two labels for the same number.
        MetricAggregate leftMetric = new MetricAggregate(new BigDecimal("1040"), new BigDecimal("52"), new BigDecimal("50"), 20);
        MetricAggregate rightMetric = new MetricAggregate(new BigDecimal("1000"), new BigDecimal("50"), new BigDecimal("40"), 20);
        Map<Metric, MetricAggregate> leftMetrics = new EnumMap<>(Metric.class);
        Map<Metric, MetricAggregate> rightMetrics = new EnumMap<>(Metric.class);
        for (Metric metric : Metric.values()) {
            leftMetrics.put(metric, leftMetric);
            rightMetrics.put(metric, rightMetric);
        }
        BreakdownRow left = new BreakdownRow("MANUAL", "Manual", coverage(20, 20), leftMetrics);
        BreakdownRow right = new BreakdownRow("ROBOT", "Robot", coverage(20, 20), rightMetrics);
        when(store.segments(any(), eq(Dimension.ORIGIN), anyList(), any())).thenReturn(List.of(left, right));

        ComparisonResult median = service.compare(user, new PerformanceInsightService.CompareRequest(
                null, null, null, null, null, null, null, null, null,
                "ORIGIN", "MANUAL", "ROBOT", "VIEWS", "MEDIAN"));
        assertThat(median.left().medianValue()).isEqualByComparingTo("50");
        assertThat(median.direction()).isEqualTo(Direction.HIGHER_OBSERVED);

        ComparisonResult average = service.compare(user, new PerformanceInsightService.CompareRequest(
                null, null, null, null, null, null, null, null, null,
                "ORIGIN", "MANUAL", "ROBOT", "VIEWS", "AVERAGE"));
        assertThat(average.statistic()).isEqualTo(Statistic.AVERAGE);
        assertThat(average.direction()).isEqualTo(Direction.SIMILAR_OBSERVED);
    }

    // ---- insufficient sample / low coverage / too young / metric unavailable ----

    @Test
    void insufficientSampleWhenEitherSideBelowMinimum() {
        properties.setMinSampleSize(5);
        stubOrigin(row("MANUAL", 3, 100, 3), row("ROBOT", 20, 80, 20));
        ComparisonResult result = compareOrigin("MANUAL", "ROBOT");
        assertThat(result.type()).isEqualTo(ResultType.INSUFFICIENT_SAMPLE);
        assertThat(result.direction()).isNull();
        assertThat(result.absoluteDifference()).isNull();
        assertThat(result.recommendations()).extracting(Recommendation::type)
                .containsExactly(RecommendationType.COLLECT_MORE_DATA);
        assertThat(result.message()).contains("Not enough").contains("5 required");
    }

    @Test
    void missingSegmentIsTreatedAsZeroSampleInsufficientData() {
        // Only ROBOT_A comes back from the store — ROBOT_B never matched any publication.
        when(store.segments(any(), eq(Dimension.ROBOT), anyList(), any()))
                .thenReturn(List.of(row(ROBOT_A, 20, 100, 20)));
        ComparisonResult result = compareRobot(ROBOT_A, ROBOT_B);
        assertThat(result.type()).isEqualTo(ResultType.INSUFFICIENT_SAMPLE);
        assertThat(result.right().sampleCount()).isZero();
        assertThat(result.right().publicationCount()).isZero();
        assertThat(result.right().label()).isEqualTo(ROBOT_B);
    }

    @Test
    void lowCoverageWhenSampleAdequateButRatioBelowThreshold() {
        properties.setMinSampleSize(5);
        properties.setMinCoverage(new BigDecimal("0.60"));
        // 6 sampled out of 20 eligible = 0.30 coverage, below 0.60, but sampleCount(6) >= minSampleSize(5).
        stubOrigin(row("MANUAL", 6, 100, 20), row("ROBOT", 20, 80, 20));
        ComparisonResult result = compareOrigin("MANUAL", "ROBOT");
        assertThat(result.type()).isEqualTo(ResultType.LOW_COVERAGE);
        assertThat(result.recommendations()).extracting(Recommendation::type)
                .containsExactly(RecommendationType.CHECK_ANALYTICS_COVERAGE);
    }

    @Test
    void tooYoungWhenPublicationsExistButNoneEligibleYet() {
        BreakdownRow left = new BreakdownRow(ROBOT_A, "Robot A", new Coverage(10, 0, 0, 10, 0), emptyMetrics());
        BreakdownRow right = row(ROBOT_B, 20, 80, 20);
        when(store.segments(any(), eq(Dimension.ROBOT), anyList(), any())).thenReturn(List.of(left, right));
        ComparisonResult result = compareRobot(ROBOT_A, ROBOT_B);
        assertThat(result.type()).isEqualTo(ResultType.TOO_YOUNG);
        assertThat(result.recommendations()).extracting(Recommendation::type)
                .containsExactly(RecommendationType.WAIT_FOR_OBSERVATION_WINDOW);
        assertThat(result.message()).doesNotContain("performance").contains("have not yet reached");
    }

    @Test
    void metricUnavailableWhenSnapshotsExistButNeverPopulateThisMetric() {
        properties.setMinSampleSize(5);
        // 20 publications have a snapshot at all (analyticsPublicationCount=20), all eligible,
        // but this specific metric's sample count is 0 — the provider never reports it.
        Coverage coverage = new Coverage(20, 20, 20, 0, 0);
        BreakdownRow left = new BreakdownRow(ROBOT_A, "Robot A", coverage, emptyMetrics());
        BreakdownRow right = row(ROBOT_B, 20, 80, 20);
        when(store.segments(any(), eq(Dimension.ROBOT), anyList(), any())).thenReturn(List.of(left, right));
        ComparisonResult result = compareRobot(ROBOT_A, ROBOT_B);
        assertThat(result.type()).isEqualTo(ResultType.METRIC_UNAVAILABLE);
        assertThat(result.recommendations()).extracting(Recommendation::type)
                .containsExactly(RecommendationType.CHECK_ANALYTICS_COVERAGE);
    }

    // ---- validation / bounds ----

    @Test
    void rejectsComparingASegmentToItself() {
        assertThatThrownBy(() -> service.compare(user, new PerformanceInsightService.CompareRequest(
                null, null, null, null, null, null, null, null, null,
                "ORIGIN", "MANUAL", "MANUAL", "VIEWS", "MEDIAN")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("itself");
    }

    @Test
    void rejectsUnsupportedDimensionMetricStatistic() {
        assertThatThrownBy(() -> service.compare(user, new PerformanceInsightService.CompareRequest(
                null, null, null, null, null, null, null, null, null,
                "NOT_A_DIMENSION", "MANUAL", "ROBOT", "VIEWS", "MEDIAN")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.compare(user, new PerformanceInsightService.CompareRequest(
                null, null, null, null, null, null, null, null, null,
                "ORIGIN", "MANUAL", "ROBOT", "NOT_A_METRIC", "MEDIAN")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.compare(user, new PerformanceInsightService.CompareRequest(
                null, null, null, null, null, null, null, null, null,
                "ORIGIN", "MANUAL", "ROBOT", "VIEWS", "MODE")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsInvalidUuidSegmentForEntityDimensions() {
        assertThatThrownBy(() -> service.compare(user, new PerformanceInsightService.CompareRequest(
                null, null, null, null, null, null, null, null, null,
                "ROBOT", "not-a-uuid", "NONE", "VIEWS", "MEDIAN")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsInvalidFixedVocabularySegments() {
        assertThatThrownBy(() -> service.compare(user, new PerformanceInsightService.CompareRequest(
                null, null, null, null, null, null, null, null, null,
                "ORIGIN", "SOMETHING_ELSE", "ROBOT", "VIEWS", "MEDIAN")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.compare(user, new PerformanceInsightService.CompareRequest(
                null, null, null, null, null, null, null, null, null,
                "AI_USAGE", "AI_APPLIED", "MAYBE", "VIEWS", "MEDIAN")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.compare(user, new PerformanceInsightService.CompareRequest(
                null, null, null, null, null, null, null, null, null,
                "PROVIDER", "TEST", "TIKTOK", "VIEWS", "MEDIAN")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void acceptsNoneAsAValidEntitySegment() {
        stubRobot(row("NONE", 20, 100, 20), row(ROBOT_A, 20, 80, 20));
        ComparisonResult result = compareRobot("NONE", ROBOT_A);
        assertThat(result.type()).isEqualTo(ResultType.DIRECTIONAL_COMPARISON);
    }

    // ---- determinism ----

    @Test
    void sameInputsProduceTheSameIdAndDifferentTypesProduceDifferentIds() {
        stubOrigin(row("MANUAL", 20, 100, 20), row("ROBOT", 20, 80, 20));
        ComparisonResult first = compareOrigin("MANUAL", "ROBOT");
        ComparisonResult second = compareOrigin("MANUAL", "ROBOT");
        assertThat(first.id()).isEqualTo(second.id());
        assertThat(first.engineVersion()).isEqualTo(PerformanceInsightService.ENGINE_VERSION);

        properties.setMinSampleSize(50);
        ComparisonResult insufficient = compareOrigin("MANUAL", "ROBOT");
        assertThat(insufficient.id()).isNotEqualTo(first.id());
    }

    // ---- limitations / disclaimer ----

    @Test
    void alwaysIncludesTheCausalityDisclaimerAndTestLimitationWhenProviderUnfiltered() {
        stubOrigin(row("MANUAL", 20, 100, 20), row("ROBOT", 20, 80, 20));
        ComparisonResult result = compareOrigin("MANUAL", "ROBOT");
        assertThat(result.limitations()).anyMatch(limitation -> limitation.contains("do not establish causation"));
        assertThat(result.limitations()).anyMatch(limitation -> limitation.contains("TEST analytics are deterministic"));
    }

    // ---- automatic /insights ----

    @Test
    void automaticInsightsProducesExactlyFourBoundedObservationsForOriginAndAiUsage() {
        when(store.summary(any(), any())).thenReturn(new Summary(
                new Filters(query.from(), query.to(), query.window(), null, null, null, null, null, null),
                new Coverage(30, 25, 25, 5, 0), emptyMetrics()));
        stubPair(Dimension.ORIGIN, "MANUAL", "ROBOT");
        stubPair(Dimension.AI_USAGE, "AI_APPLIED", "NO_APPLIED_AI");

        InsightsResponse response = service.insights(user, new PerformanceInsightService.InsightsRequest(
                null, null, null, null, null, null, null, null, null));

        assertThat(response.engineVersion()).isEqualTo(PerformanceInsightService.ENGINE_VERSION);
        assertThat(response.observations()).hasSize(4);
        assertThat(response.observations()).extracting(ComparisonResult::dimension)
                .containsExactly(Dimension.ORIGIN, Dimension.ORIGIN, Dimension.AI_USAGE, Dimension.AI_USAGE);
        assertThat(response.notices()).anyMatch(notice -> notice.message().contains("5 recent publication"));
    }

    @Test
    void emptyPopulationProducesAnEmptyNoticeAndInsufficientSampleObservations() {
        when(store.summary(any(), any())).thenReturn(new Summary(
                new Filters(query.from(), query.to(), query.window(), null, null, null, null, null, null),
                new Coverage(0, 0, 0, 0, 0), emptyMetrics()));
        when(store.segments(any(), any(), anyList(), any())).thenReturn(List.of());

        InsightsResponse response = service.insights(user, new PerformanceInsightService.InsightsRequest(
                null, null, null, null, null, null, null, null, null));

        assertThat(response.notices()).extracting(Notice::type).containsExactly("EMPTY");
        assertThat(response.observations()).hasSize(4);
        assertThat(response.observations()).allMatch(observation -> observation.type() == ResultType.INSUFFICIENT_SAMPLE);
    }

    // ---- helpers ----

    private ComparisonResult compareOrigin(String left, String right) {
        return service.compare(user, new PerformanceInsightService.CompareRequest(
                null, null, null, null, null, null, null, null, null,
                "ORIGIN", left, right, "VIEWS", "MEDIAN"));
    }

    private ComparisonResult compareRobot(String left, String right) {
        return service.compare(user, new PerformanceInsightService.CompareRequest(
                null, null, null, null, null, null, null, null, null,
                "ROBOT", left, right, "VIEWS", "MEDIAN"));
    }

    private void stubOrigin(BreakdownRow left, BreakdownRow right) {
        when(store.segments(any(), eq(Dimension.ORIGIN), anyList(), any())).thenReturn(List.of(left, right));
    }

    private void stubRobot(BreakdownRow left, BreakdownRow right) {
        when(store.segments(any(), eq(Dimension.ROBOT), anyList(), any())).thenReturn(List.of(left, right));
    }

    private void stubPair(Dimension dimension, String leftKey, String rightKey) {
        when(store.segments(any(), eq(dimension), anyList(), any()))
                .thenReturn(List.of(row(leftKey, 20, 100, 20), row(rightKey, 20, 90, 20)));
    }

    private static BreakdownRow row(String key, long sampleCount, long value, long eligible) {
        return new BreakdownRow(key, key, coverage(sampleCount, eligible), metricsFor(value, sampleCount));
    }

    private static Coverage coverage(long sampleCount, long eligible) {
        return new Coverage(eligible, eligible, eligible, 0, eligible - sampleCount);
    }

    private static Map<Metric, MetricAggregate> metricsFor(long value, long sampleCount) {
        Map<Metric, MetricAggregate> metrics = new EnumMap<>(Metric.class);
        BigDecimal decimalValue = BigDecimal.valueOf(value);
        for (Metric metric : Metric.values()) {
            metrics.put(metric, new MetricAggregate(decimalValue, decimalValue, decimalValue, sampleCount));
        }
        return metrics;
    }

    private static Map<Metric, MetricAggregate> emptyMetrics() {
        Map<Metric, MetricAggregate> metrics = new EnumMap<>(Metric.class);
        for (Metric metric : Metric.values()) {
            metrics.put(metric, new MetricAggregate(null, null, null, 0));
        }
        return metrics;
    }
}
