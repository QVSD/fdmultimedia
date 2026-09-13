package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicHighlightAnalyzerTest {

    private final DeterministicHighlightAnalyzer analyzer = new DeterministicHighlightAnalyzer();

    @Test
    void producesDeterministicBoundedCandidatesForNormalVideo() {
        HighlightAnalysisAuthorization authorization = authorization(20_000, 10, 3_000, 60_000);

        HighlightAnalysisResult first = analyzer.analyze(authorization);
        HighlightAnalysisResult second = analyzer.analyze(authorization);

        assertEquals(first, second);
        assertEquals(3, first.candidates().size());
        for (HighlightCandidateResult candidate : first.candidates()) {
            assertTrue(candidate.startMs() >= 0);
            assertTrue(candidate.endMs() > candidate.startMs());
            assertTrue(candidate.endMs() <= 20_000);
            assertTrue(candidate.endMs() - candidate.startMs() >= 3_000);
            assertTrue(candidate.endMs() - candidate.startMs() <= 60_000);
            assertTrue(candidate.score().compareTo(java.math.BigDecimal.ZERO) >= 0);
            assertTrue(candidate.score().compareTo(java.math.BigDecimal.ONE) <= 0);
            assertTrue(candidate.reason().contains("Deterministic Phase 7A"));
        }
    }

    @Test
    void respectsCandidateCountLimit() {
        HighlightAnalysisResult result = analyzer.analyze(authorization(20_000, 2, 3_000, 60_000));

        assertEquals(2, result.candidates().size());
    }

    @Test
    void returnsNoCandidatesForMediaShorterThanMinimumDuration() {
        HighlightAnalysisResult result = analyzer.analyze(authorization(2_000, 10, 3_000, 60_000));

        assertTrue(result.candidates().isEmpty());
    }

    private HighlightAnalysisAuthorization authorization(long durationMs, int maxCandidates, long minDurationMs, long maxDurationMs) {
        return new HighlightAnalysisAuthorization(
                UUID.randomUUID(),
                UUID.randomUUID(),
                durationMs,
                maxCandidates,
                minDurationMs,
                maxDurationMs,
                "DETERMINISTIC_V1",
                "1");
    }
}
