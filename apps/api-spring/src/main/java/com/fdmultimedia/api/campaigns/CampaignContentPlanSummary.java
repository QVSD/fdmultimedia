package com.fdmultimedia.api.campaigns;

import com.fdmultimedia.api.robots.CampaignPlanningPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CampaignContentPlanSummary(
        UUID id,
        UUID robotRunId,
        int revision,
        boolean current,
        CampaignPlanningPolicy policy,
        CampaignPlanStatus status,
        String plannerVersion,
        String provider,
        String model,
        String promptVersion,
        String campaignTitle,
        String campaignAngle,
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
        List<CampaignContentPlanItemSummary> items) {
}
