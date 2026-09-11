package com.fdmultimedia.api.assets;

import java.util.UUID;

public record WorkerInspectionAuthorizationResponse(
        UUID assetId,
        String downloadUrl,
        long maxDownloadSizeBytes,
        int connectTimeoutSeconds,
        int readTimeoutSeconds) {
}
