package com.fdmultimedia.api.campaigns;

import java.util.UUID;

public record CampaignContentPlanItemSummary(
        UUID id,
        UUID robotRunOutputId,
        int sequence,
        CampaignPlanRole role,
        String hookGuidance,
        String captionGuidance,
        String ctaGuidance,
        String avoidRepetitionGuidance) {
}
