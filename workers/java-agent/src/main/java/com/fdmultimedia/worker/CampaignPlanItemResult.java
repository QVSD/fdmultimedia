package com.fdmultimedia.worker;

import java.util.UUID;

record CampaignPlanItemResult(
        UUID outputId,
        String role,
        String hookGuidance,
        String captionGuidance,
        String ctaGuidance,
        String avoidRepetitionWithPrevious) {
}
