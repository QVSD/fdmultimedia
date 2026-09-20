package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.analytics.DashboardQuery.Dimension;
import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import java.math.BigDecimal;
import java.util.List;

/**
 * Phase 13C: deterministic, descriptive observations over the immutable
 * Phase 13A/13B analytics data. Nothing here mutates domain state, calls an
 * AI provider, or computes a performance score/ranking — see
 * {@link PerformanceInsightService} for the decision rules that produce
 * these records.
 */
public final class InsightModels {
    private InsightModels() {}

    /** Whichever statistic drives {@code direction} — the DTO still carries both. */
    public enum Statistic { AVERAGE, MEDIAN }

    /** Never BETTER/WORSE/WINNER/LOSER — these compare observed values only. */
    public enum Direction { HIGHER_OBSERVED, LOWER_OBSERVED, SIMILAR_OBSERVED }

    public enum ResultType { DIRECTIONAL_COMPARISON, INSUFFICIENT_SAMPLE, LOW_COVERAGE, TOO_YOUNG, METRIC_UNAVAILABLE }

    /** Deliberately excludes anything that would change a Robot/Persona/schedule/policy. */
    public enum RecommendationType {
        COLLECT_MORE_DATA, WAIT_FOR_OBSERVATION_WINDOW, REVIEW_CONTENT_DIFFERENCES, CHECK_ANALYTICS_COVERAGE
    }

    public record Recommendation(RecommendationType type, String message) {}

    public record SegmentEvidence(
            String segmentId, String label, long publicationCount, long eligibleByAgeCount,
            long analyticsPublicationCount, long sampleCount, BigDecimal coverage,
            BigDecimal medianValue, BigDecimal averageValue) {}

    public record ComparisonResult(
            String id, ResultType type, String engineVersion, Dimension dimension, Metric metric,
            Statistic statistic, Window observationWindow, SegmentEvidence left, SegmentEvidence right,
            BigDecimal absoluteDifference, BigDecimal relativeDifferencePercent, Direction direction,
            boolean materialDifference, String message, List<String> limitations,
            List<Recommendation> recommendations) {}

    public record Notice(String type, String message) {}

    public record InsightsResponse(
            String engineVersion, String filtersFingerprint, String disclaimer,
            List<Notice> notices, List<ComparisonResult> observations) {}
}
