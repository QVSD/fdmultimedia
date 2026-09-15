package com.fdmultimedia.api.transcripts;

import java.util.UUID;

public record WorkerTranscriptAuthorizationResponse(
        UUID transcriptId,
        UUID assetId,
        String sourceDownloadUrl,
        long maxDownloadSizeBytes,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        long sourceDurationMs,
        String provider,
        String model,
        int maxSegments,
        int maxSegmentTextLength,
        int maxTotalTextLength) {
}
