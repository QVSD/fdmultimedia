package com.fdmultimedia.api.experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.fdmultimedia.api.experiments.WelchStatistics.DescriptiveStats;
import com.fdmultimedia.api.experiments.WelchStatistics.EffectEstimate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Item 80/81: expected values for the four reference datasets below were
 * computed independently with Python 3.13.5 + SciPy 1.18.0 (never by calling
 * this production code) — see the exact script output in the Phase 14B
 * final report. {@code scipy.stats.ttest_ind(a, b, equal_var=False)} gives
 * the t-statistic/p-value/df directly (Welch's test); the CI/Hedges' g were
 * computed by hand from the same NumPy sample variances
 * (sd = a.var(ddof=1), i.e. sample variance with the n-1 denominator,
 * matching item 19). A relative tolerance of 1e-3 to 1e-4 is used throughout.
 */
class WelchStatisticsTest {

    // ---- DescriptiveStats: n=0/n=1/known small dataset ----

    @Test
    void describeOfEmptyListReturnsAllNullsNotAnException() {
        DescriptiveStats stats = DescriptiveStats.of(List.of());

        assertThat(stats.n()).isZero();
        assertThat(stats.mean()).isNull();
        assertThat(stats.median()).isNull();
        assertThat(stats.sampleVariance()).isNull();
        assertThat(stats.standardDeviation()).isNull();
        assertThat(stats.min()).isNull();
        assertThat(stats.max()).isNull();
    }

    @Test
    void describeOfASingleValueHasMeanAndMedianButNullSampleVariance() {
        DescriptiveStats stats = DescriptiveStats.of(List.of(42.0));

        assertThat(stats.n()).isEqualTo(1);
        assertThat(stats.mean()).isEqualTo(42.0);
        assertThat(stats.median()).isEqualTo(42.0);
        assertThat(stats.min()).isEqualTo(42.0);
        assertThat(stats.max()).isEqualTo(42.0);
        assertThat(stats.sampleVariance()).isNull();
        assertThat(stats.standardDeviation()).isNull();
    }

    @Test
    void describeOfKnownDatasetMatchesIndependentlyComputedReferenceValues() {
        // A = [10,12,14,16,18,20,22]: numpy mean=16.0, var(ddof=1)=18.666666666666668, median=16.0
        DescriptiveStats stats = DescriptiveStats.of(List.of(10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0));

        assertThat(stats.n()).isEqualTo(7);
        assertThat(stats.mean()).isCloseTo(16.0, offset(1e-9));
        assertThat(stats.median()).isCloseTo(16.0, offset(1e-9));
        assertThat(stats.sampleVariance()).isCloseTo(18.666666666666668, offset(1e-9));
        assertThat(stats.standardDeviation()).isCloseTo(4.320493798938574, offset(1e-9));
        assertThat(stats.min()).isEqualTo(10.0);
        assertThat(stats.max()).isEqualTo(22.0);
    }

    @Test
    void describeMedianOfEvenCountIsTheAverageOfTheTwoMiddleValues() {
        // [1,2,3,4] -> median (2+3)/2 = 2.5
        DescriptiveStats stats = DescriptiveStats.of(List.of(1.0, 2.0, 3.0, 4.0));
        assertThat(stats.median()).isEqualTo(2.5);
    }

    // ---- reference dataset 1: unequal n, CI includes zero ----

    @Test
    void referenceDataset1UnequalSampleSizesMatchesScipyWelchTTest() {
        DescriptiveStats a = DescriptiveStats.of(List.of(10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0));
        DescriptiveStats b = DescriptiveStats.of(List.of(8.0, 9.0, 11.0, 13.0, 15.0, 17.0));

        EffectEstimate effect = WelchStatistics.estimate(a, b);

        assertThat(effect.degenerateVariance()).isFalse();
        assertThat(effect.absoluteMeanDifference()).isCloseTo(3.833333333333334, offset(1e-6));
        assertThat(effect.standardError()).isCloseTo(2.1666666666666665, offset(1e-6));
        assertThat(effect.degreesOfFreedom()).isCloseTo(10.977400261357523, offset(1e-4));
        assertThat(effect.confidenceIntervalLower()).isCloseTo(-0.9366659497884209, offset(1e-3));
        assertThat(effect.confidenceIntervalUpper()).isCloseTo(8.603332616455088, offset(1e-3));
        assertThat(effect.confidenceIntervalIncludesZero()).isTrue();
        assertThat(effect.pValue()).isCloseTo(0.10459218772833409, offset(1e-4));
        assertThat(effect.standardizedEffectSize()).isCloseTo(0.8996026186646494, offset(1e-3));
    }

    // ---- reference dataset 2: equal-ish means, unequal variance ----

    @Test
    void referenceDataset2UnequalVarianceMatchesScipyWelchTTest() {
        DescriptiveStats a = DescriptiveStats.of(List.of(5.0, 5.0, 5.0, 10.0, 10.0, 15.0, 15.0, 20.0));
        DescriptiveStats b = DescriptiveStats.of(List.of(9.0, 9.0, 10.0, 10.0, 10.0, 11.0, 11.0, 12.0));

        EffectEstimate effect = WelchStatistics.estimate(a, b);

        assertThat(effect.absoluteMeanDifference()).isCloseTo(0.375, offset(1e-6));
        assertThat(effect.standardError()).isCloseTo(2.0238532908432725, offset(1e-6));
        assertThat(effect.degreesOfFreedom()).isCloseTo(7.472699315996797, offset(1e-3));
        assertThat(effect.confidenceIntervalLower()).isCloseTo(-4.349963143377818, offset(1e-3));
        assertThat(effect.confidenceIntervalUpper()).isCloseTo(5.099963143377818, offset(1e-3));
        assertThat(effect.confidenceIntervalIncludesZero()).isTrue();
        assertThat(effect.pValue()).isCloseTo(0.8579311242592148, offset(1e-4));
        assertThat(effect.standardizedEffectSize()).isCloseTo(0.08759168862425971, offset(1e-3));
    }

    // ---- reference dataset 3: large n ----

    @Test
    void referenceDataset3LargeSampleMatchesScipyWelchTTest() {
        List<Double> aValues = java.util.stream.IntStream.rangeClosed(1, 50).mapToObj(Double::valueOf).toList();
        List<Double> bValues = java.util.stream.IntStream.rangeClosed(1, 40).mapToObj(i -> i + 3.0).toList();
        DescriptiveStats a = DescriptiveStats.of(aValues);
        DescriptiveStats b = DescriptiveStats.of(bValues);

        EffectEstimate effect = WelchStatistics.estimate(a, b);

        assertThat(a.n()).isEqualTo(50);
        assertThat(b.n()).isEqualTo(40);
        assertThat(effect.absoluteMeanDifference()).isCloseTo(2.0, offset(1e-6));
        assertThat(effect.standardError()).isCloseTo(2.7688746209726913, offset(1e-5));
        assertThat(effect.degreesOfFreedom()).isCloseTo(87.99782381615597, offset(1e-2));
        assertThat(effect.confidenceIntervalLower()).isCloseTo(-3.5025583678418633, offset(1e-3));
        assertThat(effect.confidenceIntervalUpper()).isCloseTo(7.502558367841863, offset(1e-3));
        assertThat(effect.confidenceIntervalIncludesZero()).isTrue();
        assertThat(effect.pValue()).isCloseTo(0.47201597114057586, offset(1e-4));
    }

    // ---- reference dataset 4: CI excludes zero ----

    @Test
    void referenceDataset4SeparatedGroupsProducesACiThatExcludesZero() {
        DescriptiveStats a = DescriptiveStats.of(List.of(20.0, 21.0, 19.0, 20.0, 21.0, 20.0));
        DescriptiveStats b = DescriptiveStats.of(List.of(10.0, 11.0, 9.0, 10.0, 11.0, 10.0));

        EffectEstimate effect = WelchStatistics.estimate(a, b);

        assertThat(effect.absoluteMeanDifference()).isCloseTo(10.000000000000002, offset(1e-6));
        assertThat(effect.standardError()).isCloseTo(0.4346134936801766, offset(1e-6));
        assertThat(effect.degreesOfFreedom()).isCloseTo(9.999999999999998, offset(1e-3));
        assertThat(effect.confidenceIntervalLower()).isCloseTo(9.03162078913371, offset(1e-3));
        assertThat(effect.confidenceIntervalUpper()).isCloseTo(10.968379210866294, offset(1e-3));
        assertThat(effect.confidenceIntervalIncludesZero()).isFalse();
        assertThat(effect.pValue()).isLessThan(0.0001);
    }

    // ---- edge cases (item 82) ----

    @Test
    void estimateWithEitherArmEmptyReturnsAllNulls() {
        DescriptiveStats a = DescriptiveStats.of(List.of());
        DescriptiveStats b = DescriptiveStats.of(List.of(1.0, 2.0, 3.0));

        EffectEstimate effect = WelchStatistics.estimate(a, b);

        assertThat(effect.absoluteMeanDifference()).isNull();
        assertThat(effect.standardError()).isNull();
        assertThat(effect.confidenceIntervalLower()).isNull();
        assertThat(effect.pValue()).isNull();
        assertThat(effect.degenerateVariance()).isFalse();
    }

    @Test
    void estimateWithBothArmsSingleValueHasDifferenceButNoInference() {
        DescriptiveStats a = DescriptiveStats.of(List.of(10.0));
        DescriptiveStats b = DescriptiveStats.of(List.of(7.0));

        EffectEstimate effect = WelchStatistics.estimate(a, b);

        assertThat(effect.absoluteMeanDifference()).isEqualTo(3.0);
        assertThat(effect.standardError()).isNull();
        assertThat(effect.degreesOfFreedom()).isNull();
        assertThat(effect.confidenceIntervalLower()).isNull();
        assertThat(effect.pValue()).isNull();
        assertThat(effect.standardizedEffectSize()).isNull();
        assertThat(effect.degenerateVariance()).isTrue();
    }

    @Test
    void estimateWithBothArmsConstantAndEqualMeansIsDegenerateNotDivideByZero() {
        DescriptiveStats a = DescriptiveStats.of(List.of(5.0, 5.0, 5.0, 5.0, 5.0));
        DescriptiveStats b = DescriptiveStats.of(List.of(5.0, 5.0, 5.0, 5.0, 5.0));

        EffectEstimate effect = WelchStatistics.estimate(a, b);

        assertThat(effect.absoluteMeanDifference()).isEqualTo(0.0);
        assertThat(effect.relativeMeanDifferencePercent()).isEqualTo(0.0);
        assertThat(effect.standardError()).isEqualTo(0.0);
        assertThat(effect.degreesOfFreedom()).isNull();
        assertThat(effect.confidenceIntervalLower()).isNull();
        assertThat(effect.pValue()).isNull();
        assertThat(effect.degenerateVariance()).isTrue();
    }

    @Test
    void estimateWithBothArmsConstantButDifferentValuesIsDegenerateNeverFabricatesAPValue() {
        DescriptiveStats a = DescriptiveStats.of(List.of(10.0, 10.0, 10.0, 10.0, 10.0));
        DescriptiveStats b = DescriptiveStats.of(List.of(5.0, 5.0, 5.0, 5.0, 5.0));

        EffectEstimate effect = WelchStatistics.estimate(a, b);

        assertThat(effect.absoluteMeanDifference()).isEqualTo(5.0);
        assertThat(effect.standardError()).isEqualTo(0.0);
        assertThat(effect.pValue()).isNull();
        assertThat(effect.confidenceIntervalLower()).isNull();
        assertThat(effect.confidenceIntervalUpper()).isNull();
        assertThat(effect.degenerateVariance()).isTrue();
    }

    @Test
    void estimateWithOneArmZeroVarianceAndOtherNonZeroIsStillWellDefined() {
        DescriptiveStats a = DescriptiveStats.of(List.of(10.0, 10.0, 10.0, 10.0, 10.0));
        DescriptiveStats b = DescriptiveStats.of(List.of(1.0, 5.0, 9.0, 13.0, 17.0));

        EffectEstimate effect = WelchStatistics.estimate(a, b);

        assertThat(effect.degenerateVariance()).isFalse();
        assertThat(effect.standardError()).isNotNull().isGreaterThan(0.0);
        assertThat(effect.pValue()).isNotNull();
        assertThat(effect.confidenceIntervalLower()).isNotNull();
    }

    @Test
    void estimateWithEqualMeansProducesZeroDifferenceAndACiThatIncludesZero() {
        // Both means are 3.0, by construction.
        DescriptiveStats a = DescriptiveStats.of(List.of(1.0, 2.0, 3.0, 4.0, 5.0));
        DescriptiveStats b = DescriptiveStats.of(List.of(0.0, 1.5, 3.0, 4.5, 6.0));

        EffectEstimate effect = WelchStatistics.estimate(a, b);

        assertThat(a.mean()).isEqualTo(3.0);
        assertThat(b.mean()).isEqualTo(3.0);
        assertThat(effect.absoluteMeanDifference()).isCloseTo(0.0, offset(1e-9));
        assertThat(effect.confidenceIntervalIncludesZero()).isTrue();
    }

    @Test
    void estimateHandlesBMeanZeroWithoutDividingByZero() {
        DescriptiveStats a = DescriptiveStats.of(List.of(1.0, 2.0, 3.0, 4.0, 5.0));
        DescriptiveStats b = DescriptiveStats.of(List.of(-2.0, -1.0, 0.0, 1.0, 2.0));

        EffectEstimate effect = WelchStatistics.estimate(a, b);

        assertThat(b.mean()).isEqualTo(0.0);
        assertThat(effect.relativeMeanDifferencePercent()).isNull();
        assertThat(effect.absoluteMeanDifference()).isNotNull();
    }

    @Test
    void estimateWithAllZerosInBothArmsNeverProducesNaNOrInfinity() {
        DescriptiveStats a = DescriptiveStats.of(List.of(0.0, 0.0, 0.0, 0.0, 0.0));
        DescriptiveStats b = DescriptiveStats.of(List.of(0.0, 0.0, 0.0, 0.0, 0.0));

        EffectEstimate effect = WelchStatistics.estimate(a, b);

        assertThat(effect.absoluteMeanDifference()).isEqualTo(0.0);
        assertThat(effect.relativeMeanDifferencePercent()).isNull();
        assertThat(effect.degenerateVariance()).isTrue();
        assertNoNanOrInfinity(effect);
    }

    @Test
    void estimateWithHighlyUnequalSampleSizesNeverProducesNaNOrInfinity() {
        List<Double> aValues = java.util.stream.IntStream.range(0, 500).mapToObj(i -> (double) (i % 17)).toList();
        DescriptiveStats a = DescriptiveStats.of(aValues);
        DescriptiveStats b = DescriptiveStats.of(List.of(3.0, 8.0, 12.0, 1.0, 6.0));

        EffectEstimate effect = WelchStatistics.estimate(a, b);

        assertThat(a.n()).isEqualTo(500);
        assertThat(b.n()).isEqualTo(5);
        assertNoNanOrInfinity(effect);
    }

    private void assertNoNanOrInfinity(EffectEstimate effect) {
        for (Double value : List.of(
                orZero(effect.absoluteMeanDifference()), orZero(effect.relativeMeanDifferencePercent()),
                orZero(effect.standardError()), orZero(effect.degreesOfFreedom()),
                orZero(effect.confidenceIntervalLower()), orZero(effect.confidenceIntervalUpper()),
                orZero(effect.pValue()), orZero(effect.standardizedEffectSize()))) {
            assertThat(value.isNaN()).isFalse();
            assertThat(value.isInfinite()).isFalse();
        }
    }

    private Double orZero(Double value) {
        return value == null ? 0.0 : value;
    }
}
