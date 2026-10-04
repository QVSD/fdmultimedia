package com.fdmultimedia.api.safety;

import static org.assertj.core.api.Assertions.assertThat;

import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.safety.PostChangeSafetyModels.*;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostChangeSafetyEvaluatorTest {
    private static final Thresholds T = new Thresholds(5, new BigDecimal("0.60"), BigDecimal.TEN);

    private static BaselineFacts baseline(String mean) {
        return new BaselineFacts(UUID.randomUUID(), "TEST", 10, new BigDecimal("0.9"), new BigDecimal(mean), "fp");
    }

    private static CohortStats post(int runs, int eligible, int sample, String mean, String... providers) {
        return new CohortStats(runs, runs, eligible, sample, mean == null ? null : new BigDecimal(mean),
                Set.of(providers.length == 0 ? new String[] {"TEST"} : providers), "digest");
    }

    private static Outcome run(BaselineFacts b, CohortStats p) {
        return PostChangeSafetyEvaluator.evaluate(false, Metric.TOTAL_INTERACTIONS, b, null, p, T);
    }

    @Test
    void tooYoungWhenNoPostChangePublicationHasMatured() {
        assertThat(run(baseline("100"), post(3, 0, 0, null)).status()).isEqualTo(EvaluationStatus.TOO_YOUNG);
        assertThat(run(baseline("100"), post(0, 0, 0, null)).reasons()).containsExactly("NO_POST_CHANGE_RUNS");
    }

    @Test
    void nullMetricStaysNullAndIsUnavailableNeverZero() {
        Outcome o = run(baseline("100"), post(8, 8, 0, null));
        assertThat(o.status()).isEqualTo(EvaluationStatus.METRIC_UNAVAILABLE);
        assertThat(o.absoluteDifference()).isNull();
    }

    @Test
    void realZeroIsAMeasuredValueNotMissing() {
        Outcome o = run(baseline("100"), post(8, 8, 8, "0"));
        assertThat(o.status()).isEqualTo(EvaluationStatus.READY_REGRESSION_OBSERVED);
        assertThat(o.relativePercent()).isEqualByComparingTo("-100");
    }

    @Test
    void sampleBelowCanonicalMinimumIsInsufficient() {
        assertThat(run(baseline("100"), post(8, 8, 4, "10")).status()).isEqualTo(EvaluationStatus.INSUFFICIENT_SAMPLE);
        assertThat(run(baseline("100"), post(8, 8, 5, "10")).status()).isEqualTo(EvaluationStatus.READY_REGRESSION_OBSERVED);
    }

    @Test
    void coverageBelowCanonicalMinimumIsLow() {
        assertThat(run(baseline("100"), post(10, 10, 5, "10")).status()).isEqualTo(EvaluationStatus.LOW_COVERAGE); // 0.50
        assertThat(run(baseline("100"), post(10, 10, 6, "10")).status()).isNotEqualTo(EvaluationStatus.LOW_COVERAGE); // 0.60 inclusive
    }

    @Test
    void nonMaterialAdverseDifferenceIsStable() {
        Outcome o = run(baseline("100"), post(10, 10, 10, "95"));
        assertThat(o.status()).isEqualTo(EvaluationStatus.READY_STABLE);
        assertThat(o.reasons()).containsExactly("NO_MATERIAL_ADVERSE_DIFFERENCE");
    }

    @Test
    void materialImprovementIsStableNeverARegression() {
        Outcome o = run(baseline("100"), post(10, 10, 10, "150"));
        assertThat(o.status()).isEqualTo(EvaluationStatus.READY_STABLE);
        assertThat(o.adverseMagnitudePercent()).isEqualByComparingTo("-50");
    }

    @Test
    void materialAdverseDifferenceIsRegressionObserved() {
        Outcome o = run(baseline("100"), post(10, 10, 10, "80"));
        assertThat(o.status()).isEqualTo(EvaluationStatus.READY_REGRESSION_OBSERVED);
        assertThat(o.absoluteDifference()).isEqualByComparingTo("-20");
        assertThat(o.relativePercent()).isEqualByComparingTo("-20");
    }

    @Test
    void exactMaterialityThresholdIsInclusiveJustBelowIsNot() {
        assertThat(run(baseline("100"), post(10, 10, 10, "90")).status()).isEqualTo(EvaluationStatus.READY_REGRESSION_OBSERVED); // exactly 10%
        assertThat(run(baseline("100"), post(10, 10, 10, "90.0001")).status()).isEqualTo(EvaluationStatus.READY_STABLE);
    }

    @Test
    void zeroBaselineHasNoInfinityAndIsNeverAnAdverseRegression() {
        Outcome o = run(baseline("0"), post(10, 10, 10, "5"));
        assertThat(o.status()).isEqualTo(EvaluationStatus.READY_STABLE);
        assertThat(o.relativePercent()).isNull();
        assertThat(o.reasons()).contains("BASELINE_ZERO_RELATIVE_UNDEFINED");
        Outcome zeroToZero = run(baseline("0"), post(10, 10, 10, "0"));
        assertThat(zeroToZero.status()).isEqualTo(EvaluationStatus.READY_STABLE);
        assertThat(zeroToZero.absoluteDifference()).isEqualByComparingTo("0");
    }

    @Test
    void providerMismatchOrMixedProvidersAreNotComparable() {
        assertThat(run(baseline("100"), post(10, 10, 10, "10", "INSTAGRAM")).status()).isEqualTo(EvaluationStatus.NOT_COMPARABLE);
        assertThat(run(baseline("100"), post(10, 10, 10, "10", "TEST", "INSTAGRAM")).status()).isEqualTo(EvaluationStatus.NOT_COMPARABLE);
    }

    @Test
    void missingBaselineIsReportedAndNeverGuessed() {
        Outcome o = PostChangeSafetyEvaluator.evaluate(false, Metric.TOTAL_INTERACTIONS, null, "BASELINE_PERSONA_MISMATCH",
                post(10, 10, 10, "10"), T);
        assertThat(o.status()).isEqualTo(EvaluationStatus.BASELINE_UNAVAILABLE);
        assertThat(o.reasons()).containsExactly("BASELINE_PERSONA_MISMATCH");
    }

    @Test
    void statusPrecedenceIsExactlyDeclaredOrder() {
        CohortStats bad = post(10, 0, 0, null);
        // SUPERSEDED beats everything, even a missing baseline.
        assertThat(PostChangeSafetyEvaluator.evaluate(true, Metric.VIEWS, null, "X", bad, T).status()).isEqualTo(EvaluationStatus.SUPERSEDED);
        // BASELINE_UNAVAILABLE beats TOO_YOUNG.
        assertThat(PostChangeSafetyEvaluator.evaluate(false, Metric.VIEWS, null, "X", bad, T).status()).isEqualTo(EvaluationStatus.BASELINE_UNAVAILABLE);
        // TOO_YOUNG beats METRIC_UNAVAILABLE/INSUFFICIENT_SAMPLE/LOW_COVERAGE.
        assertThat(run(baseline("100"), bad).status()).isEqualTo(EvaluationStatus.TOO_YOUNG);
        // METRIC_UNAVAILABLE beats INSUFFICIENT_SAMPLE.
        assertThat(run(baseline("100"), post(10, 10, 0, null)).status()).isEqualTo(EvaluationStatus.METRIC_UNAVAILABLE);
        // INSUFFICIENT_SAMPLE beats LOW_COVERAGE (sample 2 of 10 is both).
        assertThat(run(baseline("100"), post(10, 10, 2, "5")).status()).isEqualTo(EvaluationStatus.INSUFFICIENT_SAMPLE);
        // LOW_COVERAGE beats NOT_COMPARABLE.
        assertThat(run(baseline("100"), post(10, 10, 5, "5", "INSTAGRAM")).status()).isEqualTo(EvaluationStatus.LOW_COVERAGE);
        // NOT_COMPARABLE beats READY_*.
        assertThat(run(baseline("100"), post(10, 10, 10, "5", "INSTAGRAM")).status()).isEqualTo(EvaluationStatus.NOT_COMPARABLE);
        assertThat(EvaluationStatus.values()).containsExactly(EvaluationStatus.SUPERSEDED, EvaluationStatus.BASELINE_UNAVAILABLE,
                EvaluationStatus.TOO_YOUNG, EvaluationStatus.METRIC_UNAVAILABLE, EvaluationStatus.INSUFFICIENT_SAMPLE,
                EvaluationStatus.LOW_COVERAGE, EvaluationStatus.NOT_COMPARABLE, EvaluationStatus.READY_STABLE,
                EvaluationStatus.READY_REGRESSION_OBSERVED);
    }

    @Test
    void everySupportedMetricHasExplicitAdverseDirectionMetadata() {
        for (Metric m : Metric.values()) {
            assertThat(PostChangeSafetyEvaluator.directionOf(m)).as(m.name()).contains(AdverseDirection.LOWER_IS_ADVERSE);
        }
        assertThat(PostChangeSafetyEvaluator.directionOf(null)).isEmpty();
    }
}
