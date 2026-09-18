package com.fdmultimedia.worker;

import java.util.UUID;

record PublicationAuthorization(
        UUID publicationId,
        UUID assetId,
        UUID socialAccountId,
        String platform,
        String caption,
        String downloadUrl,
        String expectedChecksumSha256,
        long maxDownloadSizeBytes,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        String idempotencyKey) {
}
