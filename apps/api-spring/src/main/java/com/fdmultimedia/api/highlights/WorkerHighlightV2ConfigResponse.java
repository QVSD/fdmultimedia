package com.fdmultimedia.api.highlights;

import java.math.BigDecimal;

/**
 * Effective SEMANTIC_HIGHLIGHTS_V2 (wire name DETERMINISTIC_V2) configuration
 * handed to the Worker for one analysis, so the deterministic scoring/ranking
 * it performs uses exactly the values the backend already fingerprinted into
 * {@code HighlightAnalysis.configFingerprint} at creation time.
 */
public record WorkerHighlightV2ConfigResponse(
        long minDurationMs,
        long preferredMinDurationMs,
        long preferredMaxDurationMs,
        long maxDurationMs,
        int maxSegmentsConsidered,
        int maxCandidateStarts,
        int maxCandidateWindows,
        BigDecimal overlapSuppressionThreshold,
        BigDecimal similarityThreshold,
        BigDecimal minTranscriptCoverage,
        BigDecimal weightHook,
        BigDecimal weightCompleteness,
        BigDecimal weightInformationDensity,
        BigDecimal weightSpeechDensity,
        BigDecimal weightBoundary,
        BigDecimal weightCoverage,
        BigDecimal weightRepetitionPenalty) {
}
