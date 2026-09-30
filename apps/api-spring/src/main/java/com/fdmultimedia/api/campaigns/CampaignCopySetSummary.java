package com.fdmultimedia.api.campaigns;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CampaignCopySetSummary(
        UUID id,
        UUID robotRunId,
        UUID campaignPlanId,
        int campaignPlanRevision,
        int revision,
        boolean current,
        CampaignCopySetStatus status,
        String generatorVersion,
        String provider,
        String model,
        String promptVersion,
        String seriesTitle,
        String sharedFraming,
        String inputFingerprint,
        Map<String, Object> configSnapshot,
        String failureCode,
        String failureMessage,
        boolean stale,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Instant appliedAt,
        UUID appliedByUserId,
        Instant rejectedAt,
        UUID rejectedByUserId,
        List<CampaignCopyItemSummary> items) {
}
