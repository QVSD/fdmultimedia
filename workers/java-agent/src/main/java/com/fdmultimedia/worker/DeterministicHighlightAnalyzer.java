package com.fdmultimedia.worker;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class DeterministicHighlightAnalyzer implements HighlightAnalyzer {

    @Override
    public HighlightAnalysisResult analyze(HighlightAnalysisAuthorization authorization) {
        long duration = authorization.durationMs();
        long clipLength = Math.min(
                authorization.maxCandidateDurationMs(),
                Math.max(authorization.minCandidateDurationMs(), duration / 4));
        if (duration < authorization.minCandidateDurationMs() || clipLength <= 0) {
            return new HighlightAnalysisResult(List.of());
        }
        clipLength = Math.min(clipLength, duration);
        double[] centers = new double[] {0.20, 0.50, 0.75};
        BigDecimal[] scores = new BigDecimal[] {
                new BigDecimal("0.8200"),
                new BigDecimal("0.7600"),
                new BigDecimal("0.6900")
        };
        List<HighlightCandidateResult> candidates = new ArrayList<>();
        for (int index = 0; index < centers.length && candidates.size() < authorization.maxCandidates(); index++) {
            long center = Math.round(duration * centers[index]);
            long start = Math.max(0, center - clipLength / 2);
            long end = start + clipLength;
            if (end > duration) {
                end = duration;
                start = Math.max(0, end - clipLength);
            }
            if (end > start && end - start >= authorization.minCandidateDurationMs()) {
                candidates.add(new HighlightCandidateResult(
                        start,
                        end,
                        scores[index],
                        "Deterministic Phase 7A candidate near " + Math.round(centers[index] * 100) + "% of the timeline"));
            }
        }
        return new HighlightAnalysisResult(List.copyOf(candidates));
    }
}
