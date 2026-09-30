package com.fdmultimedia.api.campaigns;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One output's full grounding context for coordinated copy generation
 * (item 14): its own bounded evidence plus the {@code CampaignContentPlanItem}
 * role/guidance it was assigned. Never includes another output's evidence —
 * sibling awareness is handled separately as bounded style-only summaries
 * (item 15), never folded into a single output's factual grounding.
 */
public record CoordinatedCopyOutputContext(
        UUID robotRunOutputId,
        int sequence,
        long startMs,
        long endMs,
        BigDecimal score,
        String reason,
        String transcriptExcerpt,
        CampaignPlanRole role,
        String planHookGuidance,
        String planCaptionGuidance,
        String planCtaGuidance,
        String planAvoidRepetitionGuidance) {
}
