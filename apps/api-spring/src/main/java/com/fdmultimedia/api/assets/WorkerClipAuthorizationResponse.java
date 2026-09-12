package com.fdmultimedia.api.assets;

import java.util.UUID;

public record WorkerClipAuthorizationResponse(
        UUID sourceAssetId,
        UUID outputAssetId,
        String sourceDownloadUrl,
        String outputUploadUrl,
        String storageBucket,
        String storageKey,
        long maxDownloadSizeBytes,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        long startMs,
        long durationMs) {
}
