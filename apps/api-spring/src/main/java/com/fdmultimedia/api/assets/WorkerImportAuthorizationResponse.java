package com.fdmultimedia.api.assets;

import java.util.UUID;

public record WorkerImportAuthorizationResponse(
        UUID assetId,
        String sourceUrl,
        String uploadUrl,
        String storageBucket,
        String storageKey,
        long maxDownloadSizeBytes,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        int maxRedirects) {
}
