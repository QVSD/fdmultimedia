package com.fdmultimedia.api.experiments;

import java.math.BigDecimal;

/**
 * Item 16/47: two distinct coverage ratios, never one ambiguous "coverage"
 * field. {@code assignmentOutcomeCoverage} = metricSampleCount /
 * assignmentCount (how much of the whole assigned population produced an
 * observed outcome); {@code eligibleOutcomeCoverage} = metricSampleCount /
 * eligibleByAgeCount (how much of the population old enough to measure
 * actually has a snapshot). Both {@code null} when their denominator is
 * zero — never a division by zero.
 */
public record ExperimentVariantAnalysis(
        ExperimentVariantKey variantKey,
        String label,
        long assignmentCount,
        long failedRunCount,
        long publishedCount,
        long eligibleByAgeCount,
        long tooYoungCount,
        long snapshotCount,
        long metricSampleCount,
        long protocolDeviationCount,
        BigDecimal assignmentOutcomeCoverage,
        BigDecimal eligibleOutcomeCoverage,
        BigDecimal mean,
        BigDecimal median,
        BigDecimal standardDeviation,
        BigDecimal min,
        BigDecimal max) {
}
