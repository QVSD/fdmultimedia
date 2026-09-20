package com.fdmultimedia.api.experiments;

import java.math.BigDecimal;

/**
 * Item 23/25: {@code confidenceIntervalIncludesZero} is purely descriptive —
 * never translated into a winner/loser/ship/do-not-ship field anywhere in
 * this codebase. All fields beyond {@code absoluteMeanDifference}/{@code
 * relativeMeanDifferencePercent} are {@code null} unless the owning
 * population's {@code status} is {@link AnalysisStatus#READY}.
 */
public record ExperimentEffectEstimate(
        BigDecimal absoluteMeanDifference,
        BigDecimal relativeMeanDifferencePercent,
        BigDecimal standardError,
        BigDecimal degreesOfFreedom,
        BigDecimal confidenceIntervalLower,
        BigDecimal confidenceIntervalUpper,
        Boolean confidenceIntervalIncludesZero,
        BigDecimal pValue,
        BigDecimal standardizedEffectSize) {
}
