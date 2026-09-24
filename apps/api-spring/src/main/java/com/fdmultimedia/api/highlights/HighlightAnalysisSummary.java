package com.fdmultimedia.api.highlights;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        String configFingerprint,
        Map<String, Object> configSnapshot,
        BigDecimal transcriptCoverage,
        String requestedAnalyzerType,
        String effectiveAnalyzerType,
        String fallbackReason,
        UUID transcriptId,
        List<HighlightCandidateSummary> candidates) {

    public HighlightAnalysisSummary(UUID id, UUID assetId, HighlightAnalysisStatus status, UUID analysisJobId,
            String analyzerType, String analyzerVersion, String errorCode, String errorMessage,
            Instant createdAt, Instant updatedAt, Instant completedAt, List<HighlightCandidateSummary> candidates) {
        this(id, assetId, status, analysisJobId, analyzerType, analyzerVersion, errorCode, errorMessage,
                createdAt, updatedAt, completedAt, null, null, null, null, null, null, null, candidates);
    }
}
