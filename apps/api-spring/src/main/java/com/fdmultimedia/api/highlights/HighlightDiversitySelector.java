package com.fdmultimedia.api.highlights;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class HighlightDiversitySelector {
    static final String VERSION = "DIVERSITY_SELECTOR_V1";
    static final BigDecimal TEMPORAL_THRESHOLD = new BigDecimal("0.5000");
    static final BigDecimal LEXICAL_THRESHOLD = new BigDecimal("0.7000");
    static final long MINIMUM_GAP_MS = 2_000;

    SelectionResult select(List<HighlightCandidate> pool, int count) {
        List<HighlightCandidate> ordered = pool.stream().sorted(Comparator
                .comparingInt(HighlightCandidate::getRank)
                .thenComparing(HighlightCandidate::getScore, Comparator.reverseOrder())
                .thenComparingLong(HighlightCandidate::getStartMs)
                .thenComparing(HighlightCandidate::getId)).toList();
        List<HighlightCandidate> selected = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        for (HighlightCandidate candidate : ordered) {
            if (selected.size() >= count) {
                excluded.add(new Excluded(candidate, HighlightSelectionExclusionReason.SELECTION_LIMIT_REACHED,
                        null, null, null));
                continue;
            }
            Excluded duplicate = duplicateAgainst(candidate, selected);
            if (duplicate != null) excluded.add(duplicate); else selected.add(candidate);
        }
        return new SelectionResult(List.copyOf(selected), List.copyOf(excluded));
    }

    private Excluded duplicateAgainst(HighlightCandidate candidate, List<HighlightCandidate> selected) {
        for (HighlightCandidate chosen : selected) {
            BigDecimal overlap = temporalOverlap(candidate.getStartMs(), candidate.getEndMs(), chosen.getStartMs(), chosen.getEndMs());
            if (overlap.compareTo(TEMPORAL_THRESHOLD) >= 0) {
                return new Excluded(candidate, HighlightSelectionExclusionReason.TEMPORAL_OVERLAP, chosen, overlap, null);
            }
            if (gapMs(candidate, chosen) < MINIMUM_GAP_MS) {
                return new Excluded(candidate, HighlightSelectionExclusionReason.INSUFFICIENT_TEMPORAL_GAP, chosen, overlap, null);
            }
            BigDecimal lexical = lexicalSimilarity(candidate.getTranscriptExcerpt(), chosen.getTranscriptExcerpt());
            if (lexical != null && lexical.compareTo(LEXICAL_THRESHOLD) >= 0) {
                return new Excluded(candidate, HighlightSelectionExclusionReason.LEXICAL_DUPLICATE, chosen, overlap, lexical);
            }
        }
        return null;
    }

    static BigDecimal temporalOverlap(long aStart, long aEnd, long bStart, long bEnd) {
        if (aStart < 0 || bStart < 0 || aEnd <= aStart || bEnd <= bStart) throw new IllegalArgumentException("Invalid interval");
        long intersection = Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart));
        long denominator = Math.min(aEnd - aStart, bEnd - bStart);
        return BigDecimal.valueOf(intersection).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    static BigDecimal lexicalSimilarity(String left, String right) {
        Set<String> a = DeterministicTranscriptTokenizer.tokenSet(left);
        Set<String> b = DeterministicTranscriptTokenizer.tokenSet(right);
        if (a.isEmpty() || b.isEmpty()) return null;
        Set<String> intersection = new HashSet<>(a); intersection.retainAll(b);
        Set<String> union = new HashSet<>(a); union.addAll(b);
        return BigDecimal.valueOf(intersection.size()).divide(BigDecimal.valueOf(union.size()), 4, RoundingMode.HALF_UP);
    }

    private static long gapMs(HighlightCandidate a, HighlightCandidate b) {
        if (a.getEndMs() <= b.getStartMs()) return b.getStartMs() - a.getEndMs();
        if (b.getEndMs() <= a.getStartMs()) return a.getStartMs() - b.getEndMs();
        return 0;
    }

    record SelectionResult(List<HighlightCandidate> selected, List<Excluded> excluded) {}
    record Excluded(HighlightCandidate candidate, HighlightSelectionExclusionReason reason,
            HighlightCandidate conflicting, BigDecimal temporalOverlap, BigDecimal lexicalSimilarity) {}
}
