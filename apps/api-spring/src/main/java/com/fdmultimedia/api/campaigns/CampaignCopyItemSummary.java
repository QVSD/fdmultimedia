package com.fdmultimedia.api.campaigns;

import java.util.List;
import java.util.UUID;

public record CampaignCopyItemSummary(
        UUID id,
        UUID robotRunOutputId,
        UUID campaignContentPlanItemId,
        int sequence,
        String hook,
        String caption,
        List<String> hashtags,
        String shortTitle,
        String continuityNote,
        UUID contentSuggestionId) {
}
