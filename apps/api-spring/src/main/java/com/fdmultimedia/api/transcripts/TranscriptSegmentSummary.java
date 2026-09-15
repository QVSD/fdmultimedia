package com.fdmultimedia.api.transcripts;

import java.math.BigDecimal;
import java.util.UUID;

public record TranscriptSegmentSummary(
        UUID id,
        int sequence,
        long startMs,
        long endMs,
        String text,
        BigDecimal confidence) {
}
