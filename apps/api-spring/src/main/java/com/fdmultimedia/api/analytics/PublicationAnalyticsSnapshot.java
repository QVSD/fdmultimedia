package com.fdmultimedia.api.analytics;

import java.time.Instant;
import java.util.UUID;

public record PublicationAnalyticsSnapshot(
        UUID id, UUID publicationId, UUID socialAccountId, String provider, String bucketKey,
        Instant collectedAt, Instant providerObservedAt, long publicationAgeSeconds,
        Long views, Long reach, Long likes, Long comments, Long shares, Long saves,
        Long totalInteractions, Long watchTimeMs, Long averageWatchTimeMs,
        String providerMetricVersion) {
}
