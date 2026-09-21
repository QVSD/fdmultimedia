package com.fdmultimedia.worker;

import java.util.UUID;
import java.util.List;

record HighlightAnalysisAuthorization(
        UUID analysisId,
        UUID assetId,
        long durationMs,
        int maxCandidates,
        long minCandidateDurationMs,
        long maxCandidateDurationMs,
        String analyzerType,
        String analyzerVersion,
        UUID transcriptId,
        List<HighlightTranscriptSegment> transcriptSegments,
        HighlightV2Config v2Config) {

    HighlightAnalysisAuthorization(
            UUID analysisId,
            UUID assetId,
            long durationMs,
            int maxCandidates,
            long minCandidateDurationMs,
            long maxCandidateDurationMs,
            String analyzerType,
            String analyzerVersion) {
        this(
                analysisId,
                assetId,
                durationMs,
                maxCandidates,
                minCandidateDurationMs,
                maxCandidateDurationMs,
                analyzerType,
                analyzerVersion,
                null,
                List.of(),
                null);
    }
}
