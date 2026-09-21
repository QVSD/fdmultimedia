package com.fdmultimedia.worker;

import java.math.BigDecimal;

record HighlightV2Config(
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
