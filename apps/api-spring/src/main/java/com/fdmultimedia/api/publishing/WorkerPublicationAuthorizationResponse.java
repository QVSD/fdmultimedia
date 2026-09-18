package com.fdmultimedia.api.publishing;

import com.fdmultimedia.api.accounts.SocialPlatform;
import java.util.UUID;

/**
 * Everything a Worker needs to execute exactly one publish attempt. The
 * Worker never receives permanent storage or provider credentials — only a
 * short-lived presigned GET URL scoped to this one asset, and the
 * Publication id itself as the provider idempotency key.
 */
public record WorkerPublicationAuthorizationResponse(
        UUID publicationId,
        UUID assetId,
        UUID socialAccountId,
        SocialPlatform platform,
        String caption,
        String downloadUrl,
        String expectedChecksumSha256,
        long maxDownloadSizeBytes,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        String idempotencyKey) {
}
