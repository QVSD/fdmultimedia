package com.fdmultimedia.worker;

import java.math.BigDecimal;
import java.util.List;

record HighlightCandidateResult(
        long startMs,
        long endMs,
        BigDecimal score,
        String reason,
        BigDecimal hookScore,
        BigDecimal completenessScore,
        BigDecimal informationDensityScore,
        BigDecimal speechDensityScore,
        BigDecimal boundaryScore,
        BigDecimal coverageScore,
        BigDecimal sceneScore,
        BigDecimal audioBoundaryScore,
        BigDecimal repetitionPenalty,
        List<String> explanationLabels,
        String transcriptExcerpt) {

    HighlightCandidateResult(long startMs, long endMs, BigDecimal score, String reason) {
        this(startMs, endMs, score, reason, null, null, null, null, null, null, null, null, null, null, null);
    }
}
