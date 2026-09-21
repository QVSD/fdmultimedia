package com.fdmultimedia.worker;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SEMANTIC_HIGHLIGHTS_V2 (wire analyzer type {@code DETERMINISTIC_V2}):
 * deterministic, transcript-driven, multi-signal highlight candidate
 * ranking. No LLM, no embeddings, no network calls — every score is a
 * decomposable function of already-persisted transcript segment timing and
 * text, so the exact same input always produces the exact same output.
 *
 * Pipeline: sentence-boundary detection (Unicode punctuation heuristics) →
 * bounded candidate window generation → per-window feature scoring →
 * eligibility gate (minimum transcript coverage) → deterministic
 * non-maximum suppression (temporal + textual-similarity diversity) →
 * stable final ranking. Every stage is bounded independently of transcript
 * length so a multi-hour source cannot cause an O(n^2) blow-up: segment
 * count, candidate start count, and total candidate window count each have
 * their own cap (see {@link HighlightV2Config}), and NMS only ever compares
 * within that already-bounded window set.
 */
final class DeterministicMultimodalHighlightAnalyzer implements HighlightAnalyzer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final String TERMINAL_PUNCTUATION = ".!?…‽";
    private static final long CLEAN_BOUNDARY_TOLERANCE_MS = 400;
    private static final long GRADED_BOUNDARY_TOLERANCE_MS = 1500;
    private static final long LEADING_SILENCE_TOLERANCE_MS = 300;
    private static final long HOOK_WINDOW_MS = 3000;

    @Override
    public HighlightAnalysisResult analyze(HighlightAnalysisAuthorization authorization) throws Exception {
        List<HighlightTranscriptSegment> segments = authorization.transcriptSegments();
        if (segments == null || segments.isEmpty()) {
            throw new ImportFailureException("TRANSCRIPT_REQUIRED", "Transcript segments are required", true);
        }
        HighlightV2Config config = authorization.v2Config();
        if (config == null) {
            throw new ImportFailureException("ANALYSIS_INTERNAL_ERROR", "V2 configuration was not provided", false);
        }
        long duration = authorization.durationMs();
        List<HighlightTranscriptSegment> bounded = segments.size() > config.maxSegmentsConsidered()
                ? downsample(segments, config.maxSegmentsConsidered())
                : segments;

        BigDecimal transcriptCoverage = transcriptCoverage(segments, duration);

        if (duration < config.minDurationMs()) {
            return new HighlightAnalysisResult(List.of(), transcriptCoverage);
        }

        BoundarySets boundarySets = boundaries(bounded, duration);
        List<Long> starts = candidateStarts(boundarySets.all, duration, config);
        List<Window> windows = candidateWindows(starts, boundarySets.all, bounded, duration, config);
        List<ScoredWindow> scored = new ArrayList<>();
        for (Window window : windows) {
            ScoredWindow candidate = score(window, bounded, boundarySets, config);
            if (candidate.coverage.compareTo(config.minTranscriptCoverage()) >= 0) {
                scored.add(candidate);
            }
        }
        scored.sort(Comparator
                .comparing((ScoredWindow value) -> value.baseScore).reversed()
                .thenComparingLong(value -> value.window.startMs)
                .thenComparingLong(value -> value.window.endMs)
                .thenComparing(value -> value.key));

        List<HighlightCandidateResult> accepted = new ArrayList<>();
        List<ScoredWindow> acceptedWindows = new ArrayList<>();
        BigDecimal totalWeight = config.weightHook()
                .add(config.weightCompleteness())
                .add(config.weightInformationDensity())
                .add(config.weightSpeechDensity())
                .add(config.weightBoundary())
                .add(config.weightCoverage())
                .add(config.weightRepetitionPenalty());
        for (ScoredWindow candidate : scored) {
            if (accepted.size() >= authorization.maxCandidates()) {
                break;
            }
            BigDecimal maxIou = BigDecimal.ZERO;
            BigDecimal maxSimilarity = BigDecimal.ZERO;
            for (ScoredWindow existing : acceptedWindows) {
                maxIou = maxIou.max(iou(candidate.window, existing.window));
                maxSimilarity = maxSimilarity.max(similarity(candidate.tokens, existing.tokens));
            }
            if (maxIou.compareTo(config.overlapSuppressionThreshold()) >= 0
                    || maxSimilarity.compareTo(config.similarityThreshold()) >= 0) {
                continue;
            }
            BigDecimal repetitionPenalty = maxSimilarity;
            BigDecimal penalized = candidate.baseScore.multiply(totalWeight)
                    .subtract(config.weightRepetitionPenalty().multiply(repetitionPenalty));
            BigDecimal totalScore = clampUnit(divide(penalized, totalWeight));
            accepted.add(candidate.toResult(totalScore, repetitionPenalty));
            acceptedWindows.add(candidate);
        }
        return new HighlightAnalysisResult(List.copyOf(accepted), transcriptCoverage);
    }

    // ---- transcript coverage -------------------------------------------------

    private BigDecimal transcriptCoverage(List<HighlightTranscriptSegment> segments, long durationMs) {
        if (durationMs <= 0) {
            return BigDecimal.ZERO;
        }
        long spoken = 0;
        for (HighlightTranscriptSegment segment : segments) {
            spoken += Math.max(0, segment.endMs() - segment.startMs());
        }
        return clampUnit(divide(BigDecimal.valueOf(spoken), BigDecimal.valueOf(durationMs)));
    }

    /** Deterministic even-stride sample down to at most {@code cap} segments, always keeping the first and last. */
    private List<HighlightTranscriptSegment> downsample(List<HighlightTranscriptSegment> segments, int cap) {
        if (cap <= 1 || segments.size() <= cap) {
            return segments;
        }
        List<HighlightTranscriptSegment> result = new ArrayList<>(cap);
        double stride = (double) segments.size() / cap;
        for (int i = 0; i < cap; i++) {
            result.add(segments.get(Math.min(segments.size() - 1, (int) Math.floor(i * stride))));
        }
        return result;
    }

    // ---- sentence boundaries ---------------------------------------------------

    /**
     * Two boundary sets: {@code sentence} (strict — true idea starts/ends,
     * derived only from terminal punctuation and the very first/last instant)
     * drives the "clean opening/ending" judgment in hookScore and
     * completenessScore, so that judgment reflects real sentence structure
     * rather than incidental ASR chunking. {@code all} additionally includes
     * every transcript segment's own start/end (ASR segment cuts are never
     * mid-word, so they remain reasonable — if weaker — candidate positions)
     * and is what candidate generation and the graded boundaryScore use, so
     * there is always enough boundary density to work with even when a
     * transcript has little or no punctuation.
     */
    private BoundarySets boundaries(List<HighlightTranscriptSegment> segments, long durationMs) {
        TreeSet<Long> sentence = new TreeSet<>();
        sentence.add(0L);
        sentence.add(durationMs);
        TreeSet<Long> all = new TreeSet<>(sentence);
        for (HighlightTranscriptSegment segment : segments) {
            all.add(clamp(segment.startMs(), 0, durationMs));
            all.add(clamp(segment.endMs(), 0, durationMs));
            String text = segment.text() == null ? "" : segment.text();
            long segmentSpan = Math.max(1, segment.endMs() - segment.startMs());
            int length = text.length();
            if (length == 0) {
                continue;
            }
            for (int i = 0; i < length; i++) {
                if (TERMINAL_PUNCTUATION.indexOf(text.charAt(i)) >= 0) {
                    long offset = segmentSpan * (i + 1) / length;
                    long point = clamp(segment.startMs() + offset, 0, durationMs);
                    sentence.add(point);
                    all.add(point);
                }
            }
        }
        return new BoundarySets(sentence, all);
    }

    private record BoundarySets(TreeSet<Long> sentence, TreeSet<Long> all) {
    }

    /** Bounded, deterministic, timeline-spanning subsample of candidate start positions. */
    private List<Long> candidateStarts(TreeSet<Long> boundaries, long durationMs, HighlightV2Config config) {
        List<Long> all = new ArrayList<>();
        for (Long boundary : boundaries) {
            if (boundary + config.minDurationMs() <= durationMs) {
                all.add(boundary);
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }
        if (all.size() <= config.maxCandidateStarts()) {
            return all;
        }
        List<Long> sampled = new ArrayList<>(config.maxCandidateStarts());
        double stride = (double) all.size() / config.maxCandidateStarts();
        for (int i = 0; i < config.maxCandidateStarts(); i++) {
            sampled.add(all.get(Math.min(all.size() - 1, (int) Math.floor(i * stride))));
        }
        return sampled;
    }

    private List<Window> candidateWindows(List<Long> starts, TreeSet<Long> boundaries, List<HighlightTranscriptSegment> segments,
            long durationMs, HighlightV2Config config) {
        List<Window> windows = new ArrayList<>();
        for (Long start : starts) {
            if (windows.size() >= config.maxCandidateWindows()) {
                break;
            }
            Long preferredMinEnd = boundaries.ceiling(start + config.preferredMinDurationMs());
            Long preferredMaxEnd = boundaries.floor(start + config.preferredMaxDurationMs());
            Set<Long> ends = new LinkedHashSet<>();
            addIfValid(ends, preferredMinEnd, start, durationMs, config);
            addIfValid(ends, preferredMaxEnd, start, durationMs, config);
            if (ends.isEmpty()) {
                Long fallback = boundaries.ceiling(start + config.minDurationMs());
                addIfValid(ends, fallback, start, durationMs, config);
            }
            if (ends.isEmpty()) {
                long synthetic = Math.min(start + config.preferredMinDurationMs(), durationMs);
                addIfValid(ends, synthetic, start, durationMs, config);
            }
            for (Long end : ends) {
                if (windows.size() >= config.maxCandidateWindows()) {
                    break;
                }
                windows.add(new Window(start, end));
            }
        }
        return windows;
    }

    private void addIfValid(Set<Long> ends, Long end, long start, long durationMs, HighlightV2Config config) {
        if (end == null) {
            return;
        }
        long clamped = Math.min(end, durationMs);
        long dur = clamped - start;
        if (dur >= config.minDurationMs() && dur <= config.maxDurationMs()) {
            ends.add(clamped);
        }
    }

    // ---- scoring ----------------------------------------------------------

    private ScoredWindow score(Window window, List<HighlightTranscriptSegment> segments, BoundarySets boundaries, HighlightV2Config config) {
        long windowDuration = window.endMs - window.startMs;
        List<HighlightTranscriptSegment> overlapping = new ArrayList<>();
        long spokenMs = 0;
        StringBuilder text = new StringBuilder();
        for (HighlightTranscriptSegment segment : segments) {
            if (segment.endMs() <= window.startMs || segment.startMs() >= window.endMs) {
                continue;
            }
            overlapping.add(segment);
            spokenMs += Math.max(0, Math.min(segment.endMs(), window.endMs) - Math.max(segment.startMs(), window.startMs));
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(normalize(segment.text()));
        }
        String windowText = text.toString();

        BigDecimal coverage = clampUnit(divide(BigDecimal.valueOf(spokenMs), BigDecimal.valueOf(windowDuration)));
        BigDecimal speechDensity = preferredBand(coverage);
        // The graded boundaryScore uses the broad (segment-inclusive) boundary
        // set for a forgiving distance signal; startClean/endClean use only
        // the strict sentence-boundary set so "clean opening" genuinely means
        // a real sentence edge, not just wherever the ASR happened to cut.
        long gradedStartDistance = nearestDistance(window.startMs, boundaries.all);
        long gradedEndDistance = nearestDistance(window.endMs, boundaries.all);
        BigDecimal boundaryScore = boundaryScore(gradedStartDistance, gradedEndDistance);
        boolean startClean = nearestDistance(window.startMs, boundaries.sentence) <= CLEAN_BOUNDARY_TOLERANCE_MS;
        boolean endClean = nearestDistance(window.endMs, boundaries.sentence) <= CLEAN_BOUNDARY_TOLERANCE_MS;
        BigDecimal completeness = (startClean ? new BigDecimal("0.50") : BigDecimal.ZERO)
                .add(endClean ? new BigDecimal("0.50") : BigDecimal.ZERO);
        BigDecimal informationDensity = informationDensity(windowText, spokenMs);
        boolean leadingSilence = !overlapping.isEmpty()
                && overlapping.get(0).startMs() - window.startMs > LEADING_SILENCE_TOLERANCE_MS;
        boolean speechInHookWindow = overlapping.stream()
                .anyMatch(segment -> segment.startMs() < window.startMs + HOOK_WINDOW_MS && segment.endMs() > window.startMs);
        boolean questionOpening = firstSentence(windowText).indexOf('?') >= 0;
        BigDecimal hook = hookScore(startClean, questionOpening, leadingSilence, speechInHookWindow);

        List<String> explanations = new ArrayList<>();
        if (startClean) explanations.add("CLEAN_OPENING");
        if (questionOpening) explanations.add("QUESTION_OPENING");
        if (startClean && endClean) explanations.add("COMPLETE_SENTENCE_BOUNDARIES");
        if (speechDensity.compareTo(new BigDecimal("0.80")) >= 0) explanations.add("HIGH_SPEECH_DENSITY");
        if (informationDensity.compareTo(new BigDecimal("0.80")) >= 0) explanations.add("HIGH_INFORMATION_DENSITY");
        if (leadingSilence) explanations.add("LEADING_SILENCE_PENALTY");
        if (coverage.compareTo(new BigDecimal("0.50")) < 0) explanations.add("LOW_TRANSCRIPT_COVERAGE");

        BigDecimal totalWeight = config.weightHook()
                .add(config.weightCompleteness())
                .add(config.weightInformationDensity())
                .add(config.weightSpeechDensity())
                .add(config.weightBoundary())
                .add(config.weightCoverage())
                .add(config.weightRepetitionPenalty());
        BigDecimal positiveSum = config.weightHook().multiply(hook)
                .add(config.weightCompleteness().multiply(completeness))
                .add(config.weightInformationDensity().multiply(informationDensity))
                .add(config.weightSpeechDensity().multiply(speechDensity))
                .add(config.weightBoundary().multiply(boundaryScore))
                .add(config.weightCoverage().multiply(coverage));
        BigDecimal baseScore = clampUnit(divide(positiveSum, totalWeight));

        String excerpt = windowText.length() > 280 ? windowText.substring(0, 280) : windowText;
        String key = window.startMs + "-" + window.endMs + "-" + Integer.toHexString(windowText.hashCode());
        Set<String> tokens = tokenize(windowText);
        return new ScoredWindow(window, baseScore, coverage, hook, completeness, informationDensity, speechDensity,
                boundaryScore, List.copyOf(explanations), excerpt, tokens, key);
    }

    private BigDecimal hookScore(boolean startClean, boolean questionOpening, boolean leadingSilence, boolean speechInHookWindow) {
        BigDecimal score = BigDecimal.ZERO;
        if (startClean) score = score.add(new BigDecimal("0.40"));
        if (questionOpening) score = score.add(new BigDecimal("0.30"));
        if (!leadingSilence) score = score.add(new BigDecimal("0.20"));
        if (speechInHookWindow) score = score.add(new BigDecimal("0.10"));
        return clampUnit(score);
    }

    /** Graded (non-binary) boundary-alignment signal, distinct from completenessScore's pass/fail credit. */
    private BigDecimal boundaryScore(long distanceStart, long distanceEnd) {
        BigDecimal startCredit = clampUnit(BigDecimal.ONE.subtract(divide(BigDecimal.valueOf(distanceStart), BigDecimal.valueOf(GRADED_BOUNDARY_TOLERANCE_MS))));
        BigDecimal endCredit = clampUnit(BigDecimal.ONE.subtract(divide(BigDecimal.valueOf(distanceEnd), BigDecimal.valueOf(GRADED_BOUNDARY_TOLERANCE_MS))));
        return startCredit.add(endCredit).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal informationDensity(String text, long spokenMs) {
        double seconds = Math.max(spokenMs / 1000.0, 0.5);
        long nonWhitespace = text.codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
        double charsPerSecond = nonWhitespace / seconds;
        double normalizedDensity = Math.min(1.0, charsPerSecond / 14.0);
        Set<String> tokens = tokenize(text);
        List<String> allTokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            allTokens.add(matcher.group());
        }
        double uniqueRatio = allTokens.isEmpty() ? 0.0 : (double) tokens.size() / allTokens.size();
        double blended = 0.7 * normalizedDensity + 0.3 * uniqueRatio;
        return clampUnit(BigDecimal.valueOf(blended));
    }

    /** Rewards a preferred speech-density band (not "more speech is always better") — see item 21. */
    private BigDecimal preferredBand(BigDecimal coverage) {
        double c = coverage.doubleValue();
        double score;
        if (c >= 0.35 && c <= 0.85) {
            score = 1.0;
        } else if (c < 0.35) {
            score = c <= 0.10 ? 0.0 : (c - 0.10) / 0.25;
        } else {
            score = c >= 1.0 ? 0.5 : 1.0 - 0.5 * (c - 0.85) / 0.15;
        }
        return clampUnit(BigDecimal.valueOf(score));
    }

    private String firstSentence(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (TERMINAL_PUNCTUATION.indexOf(text.charAt(i)) >= 0) {
                return text.substring(0, i + 1);
            }
        }
        return text;
    }

    private long nearestDistance(long value, TreeSet<Long> boundaries) {
        Long floor = boundaries.floor(value);
        Long ceiling = boundaries.ceiling(value);
        long best = Long.MAX_VALUE;
        if (floor != null) best = Math.min(best, value - floor);
        if (ceiling != null) best = Math.min(best, ceiling - value);
        return best;
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    // ---- diversity / NMS geometry ------------------------------------------

    private BigDecimal iou(Window a, Window b) {
        long overlap = Math.max(0, Math.min(a.endMs, b.endMs) - Math.max(a.startMs, b.startMs));
        if (overlap == 0) {
            return BigDecimal.ZERO;
        }
        long union = Math.max(a.endMs, b.endMs) - Math.min(a.startMs, b.startMs);
        return clampUnit(divide(BigDecimal.valueOf(overlap), BigDecimal.valueOf(union)));
    }

    private BigDecimal similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        if (intersection.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return clampUnit(divide(BigDecimal.valueOf(intersection.size()), BigDecimal.valueOf(union.size())));
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, MathContext.DECIMAL64).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal clampUnit(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        if (value.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP);
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Window(long startMs, long endMs) {
    }

    private record ScoredWindow(
            Window window,
            BigDecimal baseScore,
            BigDecimal coverage,
            BigDecimal hook,
            BigDecimal completeness,
            BigDecimal informationDensity,
            BigDecimal speechDensity,
            BigDecimal boundaryScore,
            List<String> explanations,
            String excerpt,
            Set<String> tokens,
            String key) {

        HighlightCandidateResult toResult(BigDecimal totalScore, BigDecimal repetitionPenalty) {
            List<String> labels = new ArrayList<>(explanations);
            if (repetitionPenalty.compareTo(new BigDecimal("0.20")) > 0) {
                labels.add("REPETITIVE_CONTENT_PENALTY");
            }
            String reason = "Deterministic V2: hook " + hook + ", completeness " + completeness
                    + ", density " + informationDensity + ", speech " + speechDensity
                    + ", boundary " + boundaryScore + ", coverage " + coverage;
            return new HighlightCandidateResult(
                    window.startMs, window.endMs, totalScore, reason,
                    hook, completeness, informationDensity, speechDensity, boundaryScore, coverage,
                    null, null, repetitionPenalty, List.copyOf(labels), excerpt);
        }
    }
}
