package com.fdmultimedia.api.transcripts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MediaTranscriptSummary(
        UUID id,
        UUID assetId,
        TranscriptStatus status,
        UUID transcriptionJobId,
        String provider,
        String model,
        String detectedLanguage,
        Long durationMs,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt,
        List<TranscriptSegmentSummary> segments) {
}
