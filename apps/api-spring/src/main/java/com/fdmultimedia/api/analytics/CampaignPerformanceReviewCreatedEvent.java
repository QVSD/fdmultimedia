package com.fdmultimedia.api.analytics;

import java.util.UUID;

/** Generic Phase 17G lifecycle event; consumers remain outside the analytics dependency boundary. */
public record CampaignPerformanceReviewCreatedEvent(UUID reviewId,UUID robotId) {}
