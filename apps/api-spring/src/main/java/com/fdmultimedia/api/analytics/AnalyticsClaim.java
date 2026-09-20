package com.fdmultimedia.api.analytics;

import java.time.Instant;
import java.util.UUID;

record AnalyticsClaim(UUID publicationId, UUID workspaceId, UUID socialAccountId,
        String provider, Instant publishedAt, int ageBucket, String bucketKey,
        UUID claimToken, boolean manual) {
}
