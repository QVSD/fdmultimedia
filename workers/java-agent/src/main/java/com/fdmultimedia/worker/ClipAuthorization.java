package com.fdmultimedia.worker;

import java.util.UUID;

record ClipAuthorization(
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
