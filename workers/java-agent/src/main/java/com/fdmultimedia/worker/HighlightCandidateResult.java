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
        String transcriptExcerpt,
        BigDecimal baseScore,
        BigDecimal lexicalScore,
        BigDecimal emphasisScore,
        BigDecimal selfContainedScore,
        BigDecimal semanticScore,
        Integer wordCount,
        java.util.UUID firstTranscriptSegmentId,
        java.util.UUID lastTranscriptSegmentId,
        Long boundaryStartAdjustmentMs,
        Long boundaryEndAdjustmentMs) {

    HighlightCandidateResult(long startMs, long endMs, BigDecimal score, String reason) {
        this(startMs, endMs, score, reason, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    HighlightCandidateResult(long startMs, long endMs, BigDecimal score, String reason,
            BigDecimal hookScore, BigDecimal completenessScore, BigDecimal informationDensityScore,
            BigDecimal speechDensityScore, BigDecimal boundaryScore, BigDecimal coverageScore,
            BigDecimal sceneScore, BigDecimal audioBoundaryScore, BigDecimal repetitionPenalty,
            List<String> explanationLabels, String transcriptExcerpt) {
        this(startMs, endMs, score, reason, hookScore, completenessScore, informationDensityScore,
                speechDensityScore, boundaryScore, coverageScore, sceneScore, audioBoundaryScore,
                repetitionPenalty, explanationLabels, transcriptExcerpt, null, null, null, null, null,
                null, null, null, null, null);
    }
}
