package com.fdmultimedia.api.experiments;

import java.util.List;
import org.apache.commons.math3.distribution.TDistribution;

/**
 * Pure, stateless, deterministic statistics — no Spring, no I/O, no
 * randomness, no LLM (item 2). Every public method is safe for every input
 * (including {@code n=0}, {@code n=1}, and zero-variance samples): a
 * mathematically undefined result is always {@code null}, never {@code NaN}
 * or {@code Infinity} (item 34) — {@code ExperimentAnalysisServiceTest}
 * and this class's own tests exhaustively cover the edge cases in item 82.
 *
 * <p>Descriptive statistics use sample variance ({@code n-1} denominator,
 * item 19), never population variance. The confidence interval is Welch's
 * (unequal-variance) two-sided 95% interval for the difference in means
 * (item 18-22), with Welch-Satterthwaite degrees of freedom (item 20) and
 * the two-sided critical value/p-value from Apache Commons Math's {@link
 * TDistribution} (item 21/24) rather than a hand-rolled approximation.
 * Effect size is Hedges' g (item 26/27) — Cohen's d with the small-sample
 * bias correction.
 */
public final class WelchStatistics {

    private static final double CONFIDENCE_LEVEL = 0.95;

    private WelchStatistics() {
    }

    /** {@code n < 2} leaves {@code sampleVariance}/{@code standardDeviation} {@code null} (item 33) rather than throwing. */
    public record DescriptiveStats(long n, Double mean, Double median, Double sampleVariance,
            Double standardDeviation, Double min, Double max) {

        public static DescriptiveStats of(List<Double> rawValues) {
            List<Double> values = rawValues.stream().filter(v -> v != null && !v.isNaN() && !v.isInfinite()).sorted().toList();
            int n = values.size();
            if (n == 0) {
                return new DescriptiveStats(0, null, null, null, null, null, null);
            }
            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            double median = median(values);
            double min = values.get(0);
            double max = values.get(n - 1);
            if (n < 2) {
                return new DescriptiveStats(n, mean, median, null, null, min, max);
            }
            double sumSquaredDeviations = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
            double variance = sumSquaredDeviations / (n - 1);
            double sd = Math.sqrt(variance);
            return new DescriptiveStats(n, mean, median, variance, sd, min, max);
        }

        private static double median(List<Double> sortedValues) {
            int size = sortedValues.size();
            int mid = size / 2;
            return size % 2 == 1 ? sortedValues.get(mid) : (sortedValues.get(mid - 1) + sortedValues.get(mid)) / 2.0;
        }
    }

    /**
     * {@code degenerateVariance} is {@code true} exactly when the pooled
     * Welch variance term ({@code sA²/nA + sB²/nB}) is zero (or
     * either arm's sample variance is unavailable because {@code n < 2}) —
     * the caller (item 32) uses this to classify {@code INSUFFICIENT_VARIANCE}
     * rather than fabricating a p-value from an undefined t-statistic.
     * {@code absoluteMeanDifference}/{@code relativeMeanDifferencePercent}
     * are populated whenever both means exist, independent of
     * {@code degenerateVariance} (item 17 vs. item 32 are separate
     * concerns) — every other field is {@code null} when degenerate.
     */
    public record EffectEstimate(Double absoluteMeanDifference, Double relativeMeanDifferencePercent,
            Double standardError, Double degreesOfFreedom, Double confidenceIntervalLower,
            Double confidenceIntervalUpper, Boolean confidenceIntervalIncludesZero, Double pValue,
            Double standardizedEffectSize, boolean degenerateVariance) {

        static EffectEstimate empty() {
            return new EffectEstimate(null, null, null, null, null, null, null, null, null, false);
        }
    }

    public static EffectEstimate estimate(DescriptiveStats a, DescriptiveStats b) {
        if (a.n() == 0 || b.n() == 0 || a.mean() == null || b.mean() == null) {
            return EffectEstimate.empty();
        }
        double diff = a.mean() - b.mean();
        Double relative = b.mean() == 0.0 ? null : sanitize((diff / Math.abs(b.mean())) * 100.0);

        if (a.sampleVariance() == null || b.sampleVariance() == null) {
            // n < 2 in at least one arm: no sample variance exists, so no inference is possible (item 33).
            return new EffectEstimate(sanitize(diff), relative, null, null, null, null, null, null, null, true);
        }

        double varTermA = a.sampleVariance() / a.n();
        double varTermB = b.sampleVariance() / b.n();
        double seSquared = varTermA + varTermB;
        if (seSquared <= 0.0) {
            // Item 32: both arms constant (variance exactly zero) — Welch's t-statistic/df are undefined; never fabricate a p-value.
            return new EffectEstimate(sanitize(diff), relative, 0.0, null, null, null, null, null, null, true);
        }

        double se = Math.sqrt(seSquared);
        double dfNumerator = seSquared * seSquared;
        double dfDenominator = (varTermA * varTermA) / (a.n() - 1) + (varTermB * varTermB) / (b.n() - 1);
        double df = dfDenominator <= 0.0 ? Double.NaN : dfNumerator / dfDenominator;
        if (Double.isNaN(df) || Double.isInfinite(df) || df <= 0.0) {
            return new EffectEstimate(sanitize(diff), relative, se, null, null, null, null, null, null, true);
        }

        TDistribution distribution = new TDistribution(df);
        double alpha = 1 - CONFIDENCE_LEVEL;
        double criticalT = distribution.inverseCumulativeProbability(1 - alpha / 2);
        double marginOfError = criticalT * se;
        double lower = diff - marginOfError;
        double upper = diff + marginOfError;
        boolean includesZero = lower <= 0.0 && upper >= 0.0;

        double tStatistic = diff / se;
        double pValue = 2.0 * (1.0 - distribution.cumulativeProbability(Math.abs(tStatistic)));
        pValue = Math.min(1.0, Math.max(0.0, pValue));

        Double hedgesG = hedgesG(a, b);
        return new EffectEstimate(sanitize(diff), relative, sanitize(se), sanitize(df), sanitize(lower),
                sanitize(upper), includesZero, sanitize(pValue), hedgesG, false);
    }

    /** Cohen's d with the Hedges' small-sample bias correction (item 27). {@code null} when the pooled variance is undefined or zero. */
    private static Double hedgesG(DescriptiveStats a, DescriptiveStats b) {
        long pooledDf = a.n() + b.n() - 2;
        if (pooledDf <= 0 || a.sampleVariance() == null || b.sampleVariance() == null) {
            return null;
        }
        double pooledVariance = ((a.n() - 1) * a.sampleVariance() + (b.n() - 1) * b.sampleVariance()) / pooledDf;
        if (pooledVariance <= 0.0) {
            return null;
        }
        double pooledSd = Math.sqrt(pooledVariance);
        double cohensD = (a.mean() - b.mean()) / pooledSd;
        double correction = 1.0 - (3.0 / (4.0 * pooledDf - 1.0));
        return sanitize(cohensD * correction);
    }

    /** Item 34: the one place a {@code NaN}/{@code Infinity} could ever escape into a DTO — collapse it to {@code null} instead. */
    private static Double sanitize(Double value) {
        return value == null || value.isNaN() || value.isInfinite() ? null : value;
    }
}
