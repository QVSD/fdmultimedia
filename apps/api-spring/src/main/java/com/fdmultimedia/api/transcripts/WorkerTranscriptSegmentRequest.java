package com.fdmultimedia.api.transcripts;

import java.math.BigDecimal;

public record WorkerTranscriptSegmentRequest(
        Long startMs,
        Long endMs,
        String text,
        BigDecimal confidence) {
}
