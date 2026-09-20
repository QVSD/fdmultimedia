package com.fdmultimedia.api.analytics;

import java.time.Instant;

public record NormalizedAnalytics(
        Long views, Long reach, Long likes, Long comments, Long shares, Long saves,
        Long totalInteractions, Long watchTimeMs, Long averageWatchTimeMs,
        Instant providerObservedAt, String providerMetricVersion) {

    public NormalizedAnalytics {
        for (Long value : new Long[] {views, reach, likes, comments, shares, saves,
                totalInteractions, watchTimeMs, averageWatchTimeMs}) {
            if (value != null && value < 0) {
                throw new IllegalArgumentException("Analytics metrics cannot be negative");
            }
        }
        if (providerMetricVersion == null || !providerMetricVersion.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid analytics metric version");
        }
    }
}
