package com.fdmultimedia.api.highlights;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HighlightAnalysisSummary(
        UUID id,
        UUID assetId,
        HighlightAnalysisStatus status,
        UUID analysisJobId,
        String analyzerType,
        String analyzerVersion,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        List<HighlightCandidateSummary> candidates) {
}
