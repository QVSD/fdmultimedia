package com.fdmultimedia.worker;

import java.util.UUID;

record TranscriptionAuthorization(
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
