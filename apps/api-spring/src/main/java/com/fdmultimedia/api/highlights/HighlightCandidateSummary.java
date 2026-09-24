package com.fdmultimedia.api.highlights;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HighlightCandidateSummary(
        UUID id,
        UUID analysisId,
        UUID assetId,
        long startMs,
        long endMs,
        long durationMs,
        BigDecimal score,
        String reason,
        int rank,
        Instant createdAt,
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
        UUID firstTranscriptSegmentId,
        UUID lastTranscriptSegmentId,
        Long boundaryStartAdjustmentMs,
        Long boundaryEndAdjustmentMs) {
}
