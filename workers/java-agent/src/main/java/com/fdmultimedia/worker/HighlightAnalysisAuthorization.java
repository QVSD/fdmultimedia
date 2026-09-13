package com.fdmultimedia.worker;

import java.util.UUID;

record HighlightAnalysisAuthorization(
        UUID analysisId,
        UUID assetId,
        long durationMs,
        int maxCandidates,
        long minCandidateDurationMs,
        long maxCandidateDurationMs,
        String analyzerType,
        String analyzerVersion) {
}
