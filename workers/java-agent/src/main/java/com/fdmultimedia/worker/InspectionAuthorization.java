package com.fdmultimedia.worker;

import java.util.UUID;

record InspectionAuthorization(
        UUID assetId,
        String downloadUrl,
        long maxDownloadSizeBytes,
        int connectTimeoutSeconds,
        int readTimeoutSeconds) {
}
