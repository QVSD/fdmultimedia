package com.fdmultimedia.worker;

import java.util.List;
import java.util.UUID;

record CampaignPlanAuthorization(
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
