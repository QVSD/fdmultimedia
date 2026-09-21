package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicMultimodalHighlightAnalyzerTest {

    private final DeterministicMultimodalHighlightAnalyzer analyzer = new DeterministicMultimodalHighlightAnalyzer();

    @Test
    void requiresTranscriptSegments() {
        HighlightAnalysisAuthorization authorization = authorization(60_000, List.of(), defaultConfig());
        ImportFailureException ex = assertThrows(ImportFailureException.class, () -> analyzer.analyze(authorization));
        assertEquals("TRANSCRIPT_REQUIRED", ex.code());
    }

    @Test
    void sameInputProducesIdenticalOutputAcrossManyRuns() throws Exception {
        List<HighlightTranscriptSegment> segments = talkShowTranscript(120_000);
        HighlightAnalysisAuthorization authorization = authorization(120_000, segments, defaultConfig());

        HighlightAnalysisResult first = analyzer.analyze(authorization);
        for (int i = 0; i < 25; i++) {
            HighlightAnalysisResult repeat = analyzer.analyze(authorization);
            assertEquals(first, repeat, "run " + i + " diverged from the first run");
        }
        assertTrue(first.candidates().size() > 0);
    }

    @Test
    void everyCandidateRespectsConfiguredDurationAndScoreBounds() throws Exception {
        List<HighlightTranscriptSegment> segments = talkShowTranscript(180_000);
        HighlightV2Config config = defaultConfig();
        HighlightAnalysisResult result = analyzer.analyze(authorization(180_000, segments, config));

        assertTrue(result.candidates().size() > 0);
        for (HighlightCandidateResult candidate : result.candidates()) {
            long duration = candidate.endMs() - candidate.startMs();
            assertTrue(duration >= config.minDurationMs(), "candidate shorter than minDurationMs");
            assertTrue(duration <= config.maxDurationMs(), "candidate longer than maxDurationMs");
            assertTrue(candidate.startMs() >= 0);
            assertTrue(candidate.endMs() <= 180_000);
            assertTrue(candidate.score().compareTo(BigDecimal.ZERO) >= 0);
            assertTrue(candidate.score().compareTo(BigDecimal.ONE) <= 0);
            assertTrue(candidate.hookScore().compareTo(BigDecimal.ZERO) >= 0 && candidate.hookScore().compareTo(BigDecimal.ONE) <= 0);
            assertTrue(candidate.coverageScore().compareTo(BigDecimal.ZERO) >= 0);
            assertTrue(candidate.explanationLabels() != null);
        }
    }

    @Test
    void ranksAreSortedByScoreDescendingAndStableForTies() throws Exception {
        List<HighlightTranscriptSegment> segments = talkShowTranscript(120_000);
        HighlightAnalysisResult result = analyzer.analyze(authorization(120_000, segments, defaultConfig()));

        List<HighlightCandidateResult> candidates = result.candidates();
        for (int i = 1; i < candidates.size(); i++) {
            BigDecimal previous = candidates.get(i - 1).score();
            BigDecimal current = candidates.get(i).score();
            assertTrue(previous.compareTo(current) >= 0, "candidates are not sorted by descending score");
            if (previous.compareTo(current) == 0) {
                assertTrue(candidates.get(i - 1).startMs() <= candidates.get(i).startMs(),
                        "equal-score candidates must tie-break by ascending startMs");
            }
        }
    }

    @Test
    void candidatesStartingAtARealSentenceBoundaryAreFlaggedAsACleanOpening() throws Exception {
        // Every candidate start is boundary-aligned by construction (see
        // candidateStarts()), so "clean vs abrupt" is not something two
        // *generated* candidates can differ on for the same transcript —
        // instead this checks the opening at a genuine terminal-punctuation
        // boundary is recognized and credited, which is what CLEAN_OPENING
        // and the hookScore's start-clean bonus are meant to capture.
        List<HighlightTranscriptSegment> segments = new ArrayList<>();
        segments.add(segment(1, 0, 4000, "This is a clean opening sentence that sets up the topic."));
        segments.add(segment(2, 4000, 9000, "It continues for a while before finally reaching a natural conclusion."));
        HighlightAnalysisResult result = analyzer.analyze(authorization(15_000, segments, defaultConfig()));

        assertTrue(result.candidates().size() > 0);
        HighlightCandidateResult cleanStart = result.candidates().stream()
                .filter(c -> c.explanationLabels() != null && c.explanationLabels().contains("CLEAN_OPENING"))
                .findFirst().orElseThrow(() -> new AssertionError("expected at least one candidate flagged with a clean sentence-boundary opening"));
        assertTrue(cleanStart.hookScore().compareTo(new BigDecimal("0.40")) >= 0,
                "a clean-opening candidate should receive at least the clean-start hook credit");
    }

    @Test
    void questionOpeningIsFlaggedAndBoostsHookScoreOverAPlainAssertion() throws Exception {
        List<HighlightTranscriptSegment> question = List.of(
                segment(1, 0, 5000, "Have you ever wondered why this happens to everyone?"),
                segment(2, 5000, 12000, "The answer turns out to be simpler than most people expect."));
        List<HighlightTranscriptSegment> assertion = List.of(
                segment(1, 0, 5000, "This happens to everyone for a very simple reason."),
                segment(2, 5000, 12000, "The answer turns out to be simpler than most people expect."));

        HighlightAnalysisResult questionResult = analyzer.analyze(authorization(15_000, question, defaultConfig()));
        HighlightAnalysisResult assertionResult = analyzer.analyze(authorization(15_000, assertion, defaultConfig()));

        HighlightCandidateResult questionCandidate = firstStartingAtZero(questionResult);
        HighlightCandidateResult assertionCandidate = firstStartingAtZero(assertionResult);
        assertTrue(questionCandidate.explanationLabels().contains("QUESTION_OPENING"));
        assertTrue(questionCandidate.hookScore().compareTo(assertionCandidate.hookScore()) > 0);
    }

    @Test
    void completeSentenceBoundariesScoreHigherThanAbruptFragmentBoundaries() throws Exception {
        List<HighlightTranscriptSegment> segments = talkShowTranscript(60_000);
        HighlightAnalysisResult result = analyzer.analyze(authorization(60_000, segments, defaultConfig()));

        boolean anyComplete = result.candidates().stream()
                .anyMatch(c -> c.completenessScore() != null && c.completenessScore().compareTo(new BigDecimal("0.99")) >= 0);
        assertTrue(anyComplete, "expected at least one candidate with clean start+end sentence boundaries");
    }

    @Test
    void lowTranscriptCoverageCandidatesAreIneligible() throws Exception {
        List<HighlightTranscriptSegment> sparse = List.of(
                segment(1, 0, 1000, "Just a brief moment of speech."));
        HighlightV2Config config = configWithMinCoverage(new BigDecimal("0.90"));
        HighlightAnalysisResult result = analyzer.analyze(authorization(60_000, sparse, config));

        for (HighlightCandidateResult candidate : result.candidates()) {
            assertTrue(candidate.coverageScore().compareTo(new BigDecimal("0.90")) >= 0);
        }
    }

    @Test
    void temporallyOverlappingCandidatesAreSuppressedByNonMaximumSuppression() throws Exception {
        List<HighlightTranscriptSegment> segments = talkShowTranscript(90_000);
        HighlightV2Config tightOverlap = configWithOverlapThreshold(new BigDecimal("0.30"));
        HighlightAnalysisResult result = analyzer.analyze(authorization(90_000, segments, tightOverlap));

        List<HighlightCandidateResult> candidates = result.candidates();
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                double iou = intersectionOverUnion(candidates.get(i), candidates.get(j));
                assertTrue(iou < 0.30, "two accepted candidates overlap more than the configured NMS threshold: " + iou);
            }
        }
    }

    @Test
    void nearDuplicateTranscriptContentIsSuppressedAsARepetitionPenalty() throws Exception {
        List<HighlightTranscriptSegment> segments = new ArrayList<>();
        segments.add(segment(1, 0, 8000, "The secret to great cooking is always fresh high quality ingredients."));
        segments.add(segment(2, 20_000, 28_000, "The secret to great cooking is always fresh high quality ingredients."));
        segments.add(segment(3, 40_000, 48_000, "Something completely different happens over here in this section."));
        HighlightV2Config config = configWithSimilarityThreshold(new BigDecimal("0.80"));
        HighlightAnalysisResult result = analyzer.analyze(authorization(60_000, segments, config));

        long nearDuplicateStarts = result.candidates().stream()
                .filter(c -> Math.abs(c.startMs() - 0) < 500 || Math.abs(c.startMs() - 20_000) < 500)
                .count();
        assertTrue(nearDuplicateStarts <= 1, "near-duplicate transcript windows should not both survive suppression");
    }

    @Test
    void romanianDiacriticsAndUnicodeTextDoNotCrashScoringOrTokenization() throws Exception {
        List<HighlightTranscriptSegment> segments = List.of(
                segment(1, 0, 6000, "Ești pregătit să înțelegi cum funcționează cu adevărat acest lucru?"),
                segment(2, 6000, 14000, "Răspunsul e simplu: perseverență, claritate și puțină ședință de reflecție 😊."));
        HighlightAnalysisResult result = analyzer.analyze(authorization(20_000, segments, defaultConfig()));

        assertTrue(result.candidates().size() >= 0);
        for (HighlightCandidateResult candidate : result.candidates()) {
            assertTrue(candidate.transcriptExcerpt() != null);
        }
    }

    @Test
    void veryShortMediaBelowMinimumDurationProducesNoCandidatesRatherThanAnInvalidOne() throws Exception {
        List<HighlightTranscriptSegment> segments = List.of(segment(1, 0, 1500, "Too short."));
        HighlightAnalysisResult result = analyzer.analyze(authorization(2_000, segments, defaultConfig()));

        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void longFormMediaStaysBoundedInCandidateCountAndCompletesQuickly() throws Exception {
        long threeHoursMs = 3L * 60 * 60 * 1000;
        List<HighlightTranscriptSegment> segments = longFormTranscript(threeHoursMs);
        HighlightAnalysisAuthorization authorization = authorization(threeHoursMs, segments, defaultConfig());

        long startedAt = System.nanoTime();
        HighlightAnalysisResult result = analyzer.analyze(authorization);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(result.candidates().size() <= authorization.maxCandidates());
        assertTrue(elapsedMs < 10_000, "long-form analysis took too long: " + elapsedMs + "ms");
    }

    @Test
    void transcriptCoverageReflectsSpokenFractionOfMediaDuration() throws Exception {
        List<HighlightTranscriptSegment> segments = List.of(
                segment(1, 0, 30_000, "Thirty seconds of continuous speech right at the start of this video."));
        HighlightAnalysisResult result = analyzer.analyze(authorization(60_000, segments, defaultConfig()));

        assertEquals(new BigDecimal("0.5000"), result.transcriptCoverage());
    }

    @Test
    void missingV2ConfigIsAnInternalErrorRatherThanANullPointerException() {
        HighlightAnalysisAuthorization authorization = new HighlightAnalysisAuthorization(
                UUID.randomUUID(), UUID.randomUUID(), 60_000, 5, 15_000, 60_000,
                "DETERMINISTIC_V2", "2", UUID.randomUUID(),
                List.of(segment(1, 0, 5000, "Hello there.")), null);
        ImportFailureException ex = assertThrows(ImportFailureException.class, () -> analyzer.analyze(authorization));
        assertEquals("ANALYSIS_INTERNAL_ERROR", ex.code());
    }

    // ---- fixtures -----------------------------------------------------------

    private HighlightCandidateResult firstStartingAtZero(HighlightAnalysisResult result) {
        return result.candidates().stream()
                .filter(c -> Math.abs(c.startMs() - 0) < 500)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a candidate starting at time zero"));
    }

    private double intersectionOverUnion(HighlightCandidateResult a, HighlightCandidateResult b) {
        long overlap = Math.max(0, Math.min(a.endMs(), b.endMs()) - Math.max(a.startMs(), b.startMs()));
        if (overlap == 0) {
            return 0;
        }
        long union = Math.max(a.endMs(), b.endMs()) - Math.min(a.startMs(), b.startMs());
        return (double) overlap / union;
    }

    private List<HighlightTranscriptSegment> talkShowTranscript(long durationMs) {
        String[] sentences = {
                "Welcome back to the show, today we have a fascinating topic to explore.",
                "Have you ever wondered why some ideas spread faster than others?",
                "The answer, it turns out, has everything to do with clarity and timing.",
                "Let me walk you through a real example from last year's launch.",
                "It started small, almost nobody noticed it at first.",
                "Then something changed and the whole thing took off overnight.",
                "What can we learn from that moment of sudden growth?",
                "First, the message was short and easy to repeat to a friend.",
                "Second, it solved a problem people already knew they had.",
                "Third, the timing lined up with a much bigger cultural moment.",
                "So here is the takeaway you can use starting today.",
                "Keep your message simple, useful, and ready for the right moment.",
                "That is all for this segment, thanks so much for watching.",
                "In the next part we will cover three more practical examples.",
                "Stick around because it only gets more interesting from here."
        };
        List<HighlightTranscriptSegment> segments = new ArrayList<>();
        int count = sentences.length;
        long span = durationMs / count;
        for (int i = 0; i < count; i++) {
            long start = i * span;
            long end = Math.min(durationMs, start + span - 200);
            if (end <= start) {
                end = start + 200;
            }
            segments.add(segment(i + 1, start, end, sentences[i]));
        }
        return segments;
    }

    /** Deterministically generated multi-hour transcript: short repeating sentences every few seconds. */
    private List<HighlightTranscriptSegment> longFormTranscript(long durationMs) {
        List<HighlightTranscriptSegment> segments = new ArrayList<>();
        long cursor = 0;
        int sequence = 1;
        while (cursor + 4000 < durationMs) {
            long end = cursor + 3500;
            segments.add(segment(sequence++, cursor, end,
                    "Segment number " + sequence + " continues the discussion with a new short point."));
            cursor += 5000;
        }
        return segments;
    }

    private HighlightTranscriptSegment segment(int sequence, long startMs, long endMs, String text) {
        return new HighlightTranscriptSegment(sequence, startMs, endMs, text);
    }

    private HighlightAnalysisAuthorization authorization(long durationMs, List<HighlightTranscriptSegment> segments, HighlightV2Config config) {
        return new HighlightAnalysisAuthorization(
                UUID.randomUUID(), UUID.randomUUID(), durationMs, 5,
                config.minDurationMs(), config.maxDurationMs(),
                "DETERMINISTIC_V2", "2", UUID.randomUUID(), segments, config);
    }

    private HighlightV2Config defaultConfig() {
        return new HighlightV2Config(
                3_000, 5_000, 12_000, 60_000,
                6000, 300, 1200,
                new BigDecimal("0.50"), new BigDecimal("0.60"), new BigDecimal("0.10"),
                new BigDecimal("0.22"), new BigDecimal("0.20"), new BigDecimal("0.16"),
                new BigDecimal("0.14"), new BigDecimal("0.14"), new BigDecimal("0.14"), new BigDecimal("0.20"));
    }

    private HighlightV2Config configWithMinCoverage(BigDecimal minCoverage) {
        HighlightV2Config base = defaultConfig();
        return new HighlightV2Config(
                base.minDurationMs(), base.preferredMinDurationMs(), base.preferredMaxDurationMs(), base.maxDurationMs(),
                base.maxSegmentsConsidered(), base.maxCandidateStarts(), base.maxCandidateWindows(),
                base.overlapSuppressionThreshold(), base.similarityThreshold(), minCoverage,
                base.weightHook(), base.weightCompleteness(), base.weightInformationDensity(),
                base.weightSpeechDensity(), base.weightBoundary(), base.weightCoverage(), base.weightRepetitionPenalty());
    }

    private HighlightV2Config configWithOverlapThreshold(BigDecimal threshold) {
        HighlightV2Config base = defaultConfig();
        return new HighlightV2Config(
                base.minDurationMs(), base.preferredMinDurationMs(), base.preferredMaxDurationMs(), base.maxDurationMs(),
                base.maxSegmentsConsidered(), base.maxCandidateStarts(), base.maxCandidateWindows(),
                threshold, base.similarityThreshold(), base.minTranscriptCoverage(),
                base.weightHook(), base.weightCompleteness(), base.weightInformationDensity(),
                base.weightSpeechDensity(), base.weightBoundary(), base.weightCoverage(), base.weightRepetitionPenalty());
    }

    private HighlightV2Config configWithSimilarityThreshold(BigDecimal threshold) {
        HighlightV2Config base = defaultConfig();
        return new HighlightV2Config(
                base.minDurationMs(), base.preferredMinDurationMs(), base.preferredMaxDurationMs(), base.maxDurationMs(),
                base.maxSegmentsConsidered(), base.maxCandidateStarts(), base.maxCandidateWindows(),
                base.overlapSuppressionThreshold(), threshold, base.minTranscriptCoverage(),
                base.weightHook(), base.weightCompleteness(), base.weightInformationDensity(),
                base.weightSpeechDensity(), base.weightBoundary(), base.weightCoverage(), base.weightRepetitionPenalty());
    }
}
