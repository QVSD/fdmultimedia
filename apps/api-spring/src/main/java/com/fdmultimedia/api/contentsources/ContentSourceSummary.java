package com.fdmultimedia.api.contentsources;

import java.time.Instant;
import java.util.UUID;

public record ContentSourceSummary(
        UUID id,
        String name,
        String description,
        ContentSourceType type,
        ContentSourceStatus status,
        long assetCount,
        Instant createdAt,
        Instant updatedAt) {
}
