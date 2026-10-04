package com.fdmultimedia.api.safety;

import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.safety.PostChangeSafetyModels.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Pure, deterministic {@code POST_CHANGE_SAFETY_V1} decision logic. It has no persistence, clock, provider or LLM
 * dependency, and it never compares anything to a "latest" cohort: the baseline is passed in already frozen.
 */
public final class PostChangeSafetyEvaluator {
    private PostChangeSafetyEvaluator() {}

    /**
     * Explicit adverse-direction metadata. The switch expression is exhaustive without a default, so adding a
     * {@link Metric} fails compilation until its adverse direction is decided deliberately.
     */
    private static AdverseDirection explicitDirection(Metric metric) {
        return switch (metric) {
            // Audience-engagement counts: a lower observed value is the adverse side.
            case VIEWS, REACH, LIKES, COMMENTS, SHARES, SAVES, TOTAL_INTERACTIONS -> AdverseDirection.LOWER_IS_ADVERSE;
        };
    }

    public static Optional<AdverseDirection> directionOf(Metric metric) {
        return metric == null ? Optional.empty() : Optional.of(explicitDirection(metric));
    }

    /**
     * Status precedence (exact order, first match wins): SUPERSEDED, BASELINE_UNAVAILABLE, TOO_YOUNG,
     * METRIC_UNAVAILABLE, INSUFFICIENT_SAMPLE, LOW_COVERAGE, NOT_COMPARABLE, then READY_REGRESSION_OBSERVED or
     * READY_STABLE.
     */
    public static Outcome evaluate(boolean superseded, Metric metric, BaselineFacts baseline, String baselineProblem,
            CohortStats post, Thresholds t) {
        if (superseded) return outcome(EvaluationStatus.SUPERSEDED, "EPOCH_SUPERSEDED");
        if (baseline == null) return outcome(EvaluationStatus.BASELINE_UNAVAILABLE, baselineProblem == null ? "BASELINE_UNAVAILABLE" : baselineProblem);
        if (post.eligible() == 0) {
            return outcome(EvaluationStatus.TOO_YOUNG, post.runs() == 0 ? "NO_POST_CHANGE_RUNS" : "NO_MATURE_POST_CHANGE_PUBLICATIONS");
        }
        Optional<AdverseDirection> direction = directionOf(metric);
        if (direction.isEmpty()) return outcome(EvaluationStatus.METRIC_UNAVAILABLE, "ADVERSE_DIRECTION_UNDEFINED");
        if (post.sample() == 0 || post.mean() == null) return outcome(EvaluationStatus.METRIC_UNAVAILABLE, "NO_METRIC_VALUES");
        if (post.sample() < t.minSample()) return outcome(EvaluationStatus.INSUFFICIENT_SAMPLE, "SAMPLE_BELOW_MINIMUM");
        BigDecimal coverage = post.coverage();
        if (coverage == null || coverage.compareTo(t.minCoverage()) < 0) {
            return new Outcome(EvaluationStatus.LOW_COVERAGE, List.of("COVERAGE_BELOW_MINIMUM"), null, null, null, coverage);
        }
        if (post.providers().size() != 1 || !post.providers().contains(baseline.provider())) {
            return new Outcome(EvaluationStatus.NOT_COMPARABLE, List.of("PROVIDER_MISMATCH"), null, null, null, coverage);
        }
        BigDecimal absolute = post.mean().subtract(baseline.mean()).setScale(4, RoundingMode.HALF_UP);
        List<String> reasons = new ArrayList<>();
        BigDecimal relative = null;
        BigDecimal adverse = null;
        if (baseline.mean().signum() == 0) {
            reasons.add("BASELINE_ZERO_RELATIVE_UNDEFINED");
        } else {
            relative = absolute.multiply(BigDecimal.valueOf(100)).divide(baseline.mean().abs(), 4, RoundingMode.HALF_UP);
            adverse = direction.get() == AdverseDirection.LOWER_IS_ADVERSE ? relative.negate() : relative;
        }
        boolean regression = adverse != null && adverse.compareTo(t.materialPercent()) >= 0;
        reasons.add(regression ? "MATERIAL_ADVERSE_DIFFERENCE" : "NO_MATERIAL_ADVERSE_DIFFERENCE");
        return new Outcome(regression ? EvaluationStatus.READY_REGRESSION_OBSERVED : EvaluationStatus.READY_STABLE, List.copyOf(reasons),
                absolute, relative, adverse, coverage);
    }

    private static Outcome outcome(EvaluationStatus status, String reason) {
        return new Outcome(status, List.of(reason), null, null, null, null);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
