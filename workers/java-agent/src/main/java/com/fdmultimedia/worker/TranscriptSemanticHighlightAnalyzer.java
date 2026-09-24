package com.fdmultimedia.worker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TRANSCRIPT_SEMANTIC_V3: deterministic semantic reranking of the proven V2
 * temporal candidates. The rules are deliberately compact and versioned by
 * the analyzer name; changing them materially requires a new analyzer version.
 */
final class TranscriptSemanticHighlightAnalyzer implements HighlightAnalyzer {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:'[\\p{L}]+)?");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "from", "in", "is",
            "it", "of", "on", "or", "that", "the", "this", "to", "was", "we", "with", "you");
    private static final List<String> HOOK_PHRASES = List.of(
            "why", "how", "what if", "did you know", "here's", "the reason", "the problem",
            "the key", "the mistake", "the truth", "most people", "you need to", "this is why");
    private static final Set<String> CONTRAST = Set.of("but", "however", "actually", "instead", "surprisingly");
    private static final Set<String> CONTINUATIONS = Set.of("and", "but", "so", "because", "then", "also");

    private final DeterministicMultimodalHighlightAnalyzer v2 = new DeterministicMultimodalHighlightAnalyzer();

    @Override
    public HighlightAnalysisResult analyze(HighlightAnalysisAuthorization authorization) throws Exception {
        HighlightAnalysisResult base = v2.analyze(authorization);
        List<HighlightCandidateResult> reranked = new ArrayList<>();
        for (HighlightCandidateResult candidate : base.candidates()) {
            List<HighlightTranscriptSegment> overlap = authorization.transcriptSegments().stream()
                    .filter(s -> s.startMs() < candidate.endMs() && s.endMs() > candidate.startMs())
                    .toList();
            String text = normalize(candidate.transcriptExcerpt());
            List<String> tokens = tokens(text);
            int words = tokens.size();
            BigDecimal lexical = lexical(tokens);
            BigDecimal hook = hook(text, tokens);
            BigDecimal emphasis = emphasis(text, tokens);
            BigDecimal selfContained = selfContained(text, tokens);
            BigDecimal semantic = unit(weighted(
                    hook, "0.30", lexical, "0.25", emphasis, "0.15", selfContained, "0.30"));
            BigDecimal baseScore = candidate.score();
            BigDecimal finalScore = unit(baseScore.multiply(new BigDecimal("0.55"))
                    .add(semantic.multiply(new BigDecimal("0.45")))
                    .subtract(value(candidate.repetitionPenalty()).multiply(new BigDecimal("0.10"))));
            List<String> labels = new ArrayList<>(candidate.explanationLabels() == null ? List.of() : candidate.explanationLabels());
            if (hook.compareTo(new BigDecimal("0.50")) >= 0) labels.add("SEMANTIC_HOOK");
            if (emphasis.compareTo(new BigDecimal("0.40")) >= 0) labels.add("EMPHASIS_OR_CONTRAST");
            if (selfContained.compareTo(new BigDecimal("0.70")) >= 0) labels.add("SELF_CONTAINED_THOUGHT");
            if (value(candidate.repetitionPenalty()).compareTo(new BigDecimal("0.35")) >= 0) labels.add("REPETITION_PENALTY");
            reranked.add(new HighlightCandidateResult(
                    candidate.startMs(), candidate.endMs(), finalScore,
                    "Transcript-aware deterministic semantic score",
                    hook, candidate.completenessScore(), candidate.informationDensityScore(),
                    candidate.speechDensityScore(), candidate.boundaryScore(), candidate.coverageScore(),
                    candidate.sceneScore(), candidate.audioBoundaryScore(), candidate.repetitionPenalty(),
                    List.copyOf(labels), candidate.transcriptExcerpt(), baseScore, lexical, emphasis,
                    selfContained, semantic, words,
                    overlap.isEmpty() ? null : overlap.get(0).id(),
                    overlap.isEmpty() ? null : overlap.get(overlap.size() - 1).id(), 0L, 0L));
        }
        reranked.sort(java.util.Comparator
                .comparing(HighlightCandidateResult::score).reversed()
                .thenComparing(HighlightCandidateResult::baseScore, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparingLong(HighlightCandidateResult::startMs)
                .thenComparingLong(HighlightCandidateResult::endMs));
        return new HighlightAnalysisResult(List.copyOf(reranked), base.transcriptCoverage());
    }

    static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(normalize(text).toLowerCase(Locale.ROOT));
        while (matcher.find()) result.add(matcher.group());
        return List.copyOf(result);
    }

    private static BigDecimal lexical(List<String> tokens) {
        if (tokens.isEmpty()) return BigDecimal.ZERO.setScale(4);
        Set<String> meaningful = new HashSet<>();
        int content = 0;
        for (String token : tokens) {
            if (!STOP_WORDS.contains(token)) {
                content++;
                meaningful.add(token);
            }
        }
        BigDecimal diversity = BigDecimal.valueOf((double) meaningful.size() / Math.max(1, content));
        BigDecimal contentRatio = BigDecimal.valueOf((double) content / tokens.size());
        return unit(diversity.multiply(new BigDecimal("0.60")).add(contentRatio.multiply(new BigDecimal("0.40"))));
    }

    private static BigDecimal hook(String text, List<String> tokens) {
        String lower = text.toLowerCase(Locale.ROOT);
        double score = text.contains("?") ? 0.45 : 0;
        for (String phrase : HOOK_PHRASES) {
            if (containsPhrase(lower, phrase)) { score += 0.55; break; }
        }
        return unit(BigDecimal.valueOf(score));
    }

    private static BigDecimal emphasis(String text, List<String> tokens) {
        double score = text.contains("!") ? 0.35 : 0;
        if (tokens.stream().anyMatch(CONTRAST::contains)) score += 0.40;
        if (tokens.stream().anyMatch(t -> t.chars().allMatch(Character::isDigit))) score += 0.25;
        return unit(BigDecimal.valueOf(score));
    }

    private static BigDecimal selfContained(String text, List<String> tokens) {
        if (tokens.isEmpty()) return BigDecimal.ZERO.setScale(4);
        double score = tokens.size() >= 6 ? 0.30 : 0.10;
        if (!CONTINUATIONS.contains(tokens.get(0))) score += 0.30;
        if (text.endsWith(".") || text.endsWith("?") || text.endsWith("!")) score += 0.40;
        return unit(BigDecimal.valueOf(score));
    }

    private static boolean containsPhrase(String text, String phrase) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])")
                .matcher(text).find();
    }

    private static BigDecimal weighted(BigDecimal a, String aw, BigDecimal b, String bw,
            BigDecimal c, String cw, BigDecimal d, String dw) {
        return a.multiply(new BigDecimal(aw)).add(b.multiply(new BigDecimal(bw)))
                .add(c.multiply(new BigDecimal(cw))).add(d.multiply(new BigDecimal(dw)));
    }

    private static BigDecimal value(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static String normalize(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").trim(); }
    private static BigDecimal unit(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }
}
