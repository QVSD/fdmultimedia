package com.fdmultimedia.api.analytics;

import java.time.Instant;

public record PublicationAnalyticsState(
        Instant nextCollectionAt, Instant lastAttemptAt, Instant lastSuccessAt,
        String failureCode, String failureMessage, Instant completedAt) {
}
