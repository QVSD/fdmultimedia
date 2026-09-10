package com.fdmultimedia.worker;

import java.util.UUID;

record ImportMediaAuthorization(
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
