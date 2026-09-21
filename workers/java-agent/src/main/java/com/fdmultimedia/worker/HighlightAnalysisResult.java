package com.fdmultimedia.worker;

import java.math.BigDecimal;
import java.util.List;

record HighlightAnalysisResult(List<HighlightCandidateResult> candidates, BigDecimal transcriptCoverage) {

    HighlightAnalysisResult(List<HighlightCandidateResult> candidates) {
        this(candidates, null);
    }
}
