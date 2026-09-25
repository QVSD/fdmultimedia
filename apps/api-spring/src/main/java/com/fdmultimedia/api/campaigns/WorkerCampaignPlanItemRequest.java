package com.fdmultimedia.api.campaigns;

import java.util.UUID;

/** Raw, still-untrusted per-output plan item as parsed by the Worker — the backend performs all authoritative validation. */
public record WorkerCampaignPlanItemRequest(
        UUID outputId,
        String role,
        String hookGuidance,
        String captionGuidance,
        String ctaGuidance,
        String avoidRepetitionWithPrevious) {
}
