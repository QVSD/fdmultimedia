package com.fdmultimedia.api.campaigns;

import java.util.List;
import java.util.UUID;

/**
 * Everything a Worker needs to invoke its provider — a fully-built, bounded
 * prompt frozen at generation time, plus the fixed set of output IDs (in
 * order) the response must map to exactly. Never carries a provider secret;
 * the Worker reads its own endpoint/API key from its own environment.
 */
public record WorkerCampaignPlanAuthorizationResponse(
        UUID planId,
        UUID robotRunId,
        String provider,
        String model,
        String promptVersion,
        String prompt,
        List<UUID> expectedOutputIds,
        int maxCampaignTitleLength,
        int maxCampaignAngleLength,
        int maxHookGuidanceLength,
        int maxCaptionGuidanceLength,
        int maxCtaGuidanceLength,
        int maxAvoidanceGuidanceLength) {
}
