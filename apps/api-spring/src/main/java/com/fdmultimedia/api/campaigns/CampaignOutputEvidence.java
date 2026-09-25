package com.fdmultimedia.api.campaigns;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Bounded, already-frozen evidence for one {@code RobotRunOutput}, read
 * directly off its immutable {@code HighlightCandidate} (item 14/15) — never
 * from a Draft, which may still be mid-creation (CLIP_PENDING) when campaign
 * planning starts, and never a fresh transcript re-read.
 */
public record CampaignOutputEvidence(
        UUID robotRunOutputId,
        int sequence,
        long startMs,
        long endMs,
        BigDecimal score,
        String reason,
        String transcriptExcerpt) {
}
