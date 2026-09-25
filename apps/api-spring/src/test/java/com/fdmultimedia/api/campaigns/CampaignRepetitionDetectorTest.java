package com.fdmultimedia.api.campaigns;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CampaignRepetitionDetectorTest {

    private final CampaignRepetitionDetector detector = new CampaignRepetitionDetector();

    @Test
    void identicalTextHasSimilarityOfOne() {
        assertThat(detector.similarity("This is the hook", "This is the hook"))
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void completelyDistinctTextHasNoOverlap() {
        assertThat(detector.similarity("apple banana cherry", "xylophone zebra quartz"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void blankInputOnEitherSideIsZero() {
        assertThat(detector.similarity("", "something")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(detector.similarity("something", "  ")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(detector.similarity(null, "something")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void caseAndPunctuationDoNotAffectSimilarity() {
        assertThat(detector.similarity("Check THIS out, now!", "check this out now"))
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void partialOverlapIsBetweenZeroAndOne() {
        BigDecimal similarity = detector.similarity("the amazing keynote moment", "the amazing product launch");
        assertThat(similarity).isGreaterThan(BigDecimal.ZERO);
        assertThat(similarity).isLessThan(BigDecimal.ONE);
    }

    @Test
    void unicodeAndRomanianDiacriticsAreTokenizedConsistently() {
        assertThat(detector.similarity("Această scenă e incredibilă", "această scenă e incredibilă"))
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void nearDuplicateHonorsThreshold() {
        BigDecimal threshold = new BigDecimal("0.75");
        assertThat(detector.nearDuplicate("this is a great moment right here", "this is a great moment right here now", threshold))
                .isTrue();
        assertThat(detector.nearDuplicate("apple banana cherry", "xylophone zebra quartz", threshold)).isFalse();
    }

    @Test
    void nearDuplicateAtExactThresholdIsInclusive() {
        BigDecimal exact = detector.similarity("alpha beta gamma delta", "alpha beta gamma epsilon");
        assertThat(detector.nearDuplicate("alpha beta gamma delta", "alpha beta gamma epsilon", exact)).isTrue();
    }
}
