package com.fdmultimedia.api.highlights;

import java.math.BigDecimal;
import java.time.Instant;
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
        Instant createdAt) {
}
