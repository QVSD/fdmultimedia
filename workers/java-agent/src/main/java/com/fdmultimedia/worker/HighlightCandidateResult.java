package com.fdmultimedia.worker;

import java.math.BigDecimal;

record HighlightCandidateResult(
        long startMs,
        long endMs,
        BigDecimal score,
        String reason) {
}
