package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TranscriptSemanticHighlightAnalyzerTest {
    private final TranscriptSemanticHighlightAnalyzer analyzer = new TranscriptSemanticHighlightAnalyzer();

    @Test
    void tokenizationIsLocaleStableAndHandlesPunctuationNumbersAndUnicode() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(List.of("here's", "café", "2026", "isn't", "empty"),
                    TranscriptSemanticHighlightAnalyzer.tokens(" HERE'S, café 2026; isn't\nempty "));
            assertEquals(List.of(), TranscriptSemanticHighlightAnalyzer.tokens(" \t\n"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void semanticSignalsArePersistedAndBounded() throws Exception {
        HighlightAnalysisResult result = analyzer.analyze(auth(List.of(
                segment(1, 0, 7000, "Did you know the key reason 3 clips fail?"),
                segment(2, 7000, 15000, "Actually, most people miss this important detail."))));
        HighlightCandidateResult first = result.candidates().get(0);
        assertTrue(first.hookScore().compareTo(new BigDecimal("0.5")) >= 0);
        assertTrue(first.emphasisScore().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(first.semanticScore().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(first.score().compareTo(BigDecimal.ZERO) >= 0 && first.score().compareTo(BigDecimal.ONE) <= 0);
        assertTrue(first.wordCount() > 0);
        assertFalse(first.firstTranscriptSegmentId() == null);
    }

    @Test
    void sameInputHasStableOrderingAndScores() throws Exception {
        HighlightAnalysisAuthorization auth = auth(List.of(
                segment(1, 0, 7000, "Why does this system work so reliably?"),
                segment(2, 7000, 15000, "The reason is deterministic evidence and stable boundaries."),
                segment(3, 15000, 23000, "However, repeated repeated repeated words reduce quality.")));
        assertEquals(analyzer.analyze(auth), analyzer.analyze(auth));
    }

    private HighlightTranscriptSegment segment(int sequence, long start, long end, String text) {
        return new HighlightTranscriptSegment(UUID.randomUUID(), sequence, start, end, text);
    }

    private HighlightAnalysisAuthorization auth(List<HighlightTranscriptSegment> segments) {
        HighlightV2Config config = new HighlightV2Config(
                3_000, 5_000, 12_000, 60_000, 6000, 300, 1200,
                new BigDecimal("0.50"), new BigDecimal("0.60"), new BigDecimal("0.10"),
                new BigDecimal("0.22"), new BigDecimal("0.20"), new BigDecimal("0.16"),
                new BigDecimal("0.14"), new BigDecimal("0.14"), new BigDecimal("0.14"), new BigDecimal("0.20"));
        return new HighlightAnalysisAuthorization(UUID.randomUUID(), UUID.randomUUID(), 30_000, 5,
                3_000, 60_000, "DETERMINISTIC_V3", "TRANSCRIPT_SEMANTIC_V3",
                UUID.randomUUID(), segments, config);
    }
}
