package com.fdmultimedia.api.contentsuggestions;

import java.util.UUID;

/**
 * Plain-value carrier for one applied {@code CampaignContentPlanItem}'s
 * guidance — this package stays free of a compile-time dependency on
 * {@code com.fdmultimedia.api.campaigns}, exactly like {@code robotRunId}/
 * {@code robotRunOutputId} above are bare UUIDs rather than JPA
 * relationships. {@code RobotMultiOutputOrchestrator} (which already depends
 * on both packages) is what converts a real {@code CampaignContentPlanItem}
 * into this shape.
 */
public record CampaignGuidance(
        UUID campaignPlanId,
        int campaignPlanRevision,
        UUID campaignPlanItemId,
        String role,
        String hookGuidance,
        String captionGuidance,
        String ctaGuidance,
        String avoidRepetitionGuidance) {
}
