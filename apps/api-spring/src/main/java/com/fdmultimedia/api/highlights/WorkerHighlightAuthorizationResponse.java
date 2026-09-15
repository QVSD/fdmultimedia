package com.fdmultimedia.api.highlights;

import java.util.UUID;
import java.util.List;

public record WorkerHighlightAuthorizationResponse(
        UUID analysisId,
        UUID assetId,
        long durationMs,
        int maxCandidates,
        long minCandidateDurationMs,
        long maxCandidateDurationMs,
        String analyzerType,
        String analyzerVersion,
        UUID transcriptId,
        List<WorkerHighlightTranscriptSegmentResponse> transcriptSegments) {
}
