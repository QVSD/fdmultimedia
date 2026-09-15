package com.fdmultimedia.worker;

import java.math.BigDecimal;

record TranscriptSegmentResult(
        long startMs,
        long endMs,
        String text,
        BigDecimal confidence) {
}
