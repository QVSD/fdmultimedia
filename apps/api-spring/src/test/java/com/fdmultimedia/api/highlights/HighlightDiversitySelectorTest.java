package com.fdmultimedia.api.highlights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HighlightDiversitySelectorTest {
    private final HighlightDiversitySelector selector = new HighlightDiversitySelector();

    @Test void temporalOverlapCoversBoundariesAndContainment() {
        assertThat(HighlightDiversitySelector.temporalOverlap(0, 1000, 1000, 2000)).isEqualByComparingTo("0.0000");
        assertThat(HighlightDiversitySelector.temporalOverlap(0, 1000, 500, 1500)).isEqualByComparingTo("0.5000");
        assertThat(HighlightDiversitySelector.temporalOverlap(0, 2000, 500, 1500)).isEqualByComparingTo("1.0000");
        assertThat(HighlightDiversitySelector.temporalOverlap(0, 1000, 0, 1000)).isEqualByComparingTo("1.0000");
        assertThat(HighlightDiversitySelector.temporalOverlap(0, 1000, 750, 1750)).isEqualByComparingTo("0.2500");
    }

    @Test void temporalOverlapRejectsInvalidIntervals() {
        assertThatThrownBy(() -> HighlightDiversitySelector.temporalOverlap(0, 0, 1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HighlightDiversitySelector.temporalOverlap(-1, 2, 3, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void lexicalSimilarityUsesNormalizedTokenSetJaccard() {
        assertThat(HighlightDiversitySelector.lexicalSimilarity("Hello, HELLO world!", "hello world")).isEqualByComparingTo("1.0000");
        assertThat(HighlightDiversitySelector.lexicalSimilarity("one two", "three four")).isEqualByComparingTo("0.0000");
        assertThat(HighlightDiversitySelector.lexicalSimilarity("one two three", "two three four")).isEqualByComparingTo("0.5000");
        assertThat(HighlightDiversitySelector.lexicalSimilarity("Știință 2026", "știință 2026")).isEqualByComparingTo("1.0000");
        assertThat(HighlightDiversitySelector.lexicalSimilarity("", "words")).isNull();
    }

    @Test void selectsTopCountWhenCandidatesAreDistinct() {
        var result = selector.select(List.of(c(1, 0, 5000, ".90", "alpha idea complete"),
                c(2, 8000, 13000, ".80", "beta topic different"), c(3, 16000, 21000, ".70", "gamma subject unique")), 3);
        assertThat(result.selected()).extracting(HighlightCandidate::getRank).containsExactly(1, 2, 3);
        assertThat(result.excluded()).isEmpty();
    }

    @Test void skipsTemporalDuplicateAtExactThreshold() {
        var result = selector.select(List.of(c(1, 0, 10000, ".90", "alpha"), c(2, 5000, 15000, ".89", "beta"),
                c(3, 18000, 23000, ".70", "gamma")), 2);
        assertThat(result.selected()).extracting(HighlightCandidate::getRank).containsExactly(1, 3);
        assertThat(result.excluded().get(0).reason()).isEqualTo(HighlightSelectionExclusionReason.TEMPORAL_OVERLAP);
        assertThat(result.excluded().get(0).temporalOverlap()).isEqualByComparingTo("0.5000");
        assertThat(result.excluded().get(0).conflicting().getRank()).isEqualTo(1);
    }

    @Test void skipsNearAdjacentCandidate() {
        var result = selector.select(List.of(c(1, 0, 5000, ".90", "alpha"), c(2, 6500, 10000, ".80", "beta")), 2);
        assertThat(result.selected()).hasSize(1);
        assertThat(result.excluded().get(0).reason()).isEqualTo(HighlightSelectionExclusionReason.INSUFFICIENT_TEMPORAL_GAP);
    }

    @Test void skipsLexicalDuplicateAgainstAnySelectedItem() {
        var result = selector.select(List.of(c(1, 0, 4000, ".90", "one two three four"),
                c(2, 7000, 11000, ".80", "alpha beta gamma"),
                c(3, 14000, 18000, ".70", "alpha beta gamma delta")), 3);
        assertThat(result.selected()).extracting(HighlightCandidate::getRank).containsExactly(1, 2);
        assertThat(result.excluded().get(0).reason()).isEqualTo(HighlightSelectionExclusionReason.LEXICAL_DUPLICATE);
        assertThat(result.excluded().get(0).conflicting().getRank()).isEqualTo(2);
        assertThat(result.excluded().get(0).lexicalSimilarity()).isEqualByComparingTo("0.7500");
    }

    @Test void reportsPartialAndSelectionLimitInputsDeterministically() {
        List<HighlightCandidate> pool = List.of(c(2, 8000, 12000, ".80", "beta"), c(1, 0, 4000, ".90", "alpha"),
                c(3, 16000, 20000, ".70", "gamma"));
        var first = selector.select(pool, 1);
        var second = selector.select(pool, 1);
        assertThat(first.selected()).extracting(HighlightCandidate::getRank).containsExactly(1);
        assertThat(first.excluded()).allMatch(e -> e.reason() == HighlightSelectionExclusionReason.SELECTION_LIMIT_REACHED);
        assertThat(second.selected().get(0).getId()).isEqualTo(first.selected().get(0).getId());
    }

    @Test void supportsEmptyPoolAndMaximumCount() {
        assertThat(selector.select(List.of(), 5).selected()).isEmpty();
        assertThat(selector.select(List.of(c(1, 0, 1000, ".9", "a")), 5).selected()).hasSize(1);
    }

    private HighlightCandidate c(int rank, long start, long end, String score, String excerpt) {
        HighlightCandidate candidate = mock(HighlightCandidate.class);
        when(candidate.getId()).thenReturn(UUID.nameUUIDFromBytes((rank + ":" + start).getBytes()));
        when(candidate.getRank()).thenReturn(rank); when(candidate.getStartMs()).thenReturn(start); when(candidate.getEndMs()).thenReturn(end);
        when(candidate.getScore()).thenReturn(new BigDecimal(score)); when(candidate.getTranscriptExcerpt()).thenReturn(excerpt);
        return candidate;
    }
}
