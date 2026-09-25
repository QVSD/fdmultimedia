package com.fdmultimedia.api.campaigns;

import com.fdmultimedia.api.highlights.DeterministicTranscriptTokenizer;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deterministic lexical-overlap detection for campaign guidance (item 32) —
 * the exact same tokenizer/Jaccard approach the V2 highlight analyzer uses
 * for candidate-repetition suppression, applied here to hook/caption
 * guidance text instead of transcript excerpts. No embeddings, no semantic
 * similarity model (explicitly out of scope): this only ever answers "do
 * these two guidance strings share a lot of literal words," which is enough
 * to reject an AI plan that proposes near-identical hooks for two outputs
 * (item 33) without trusting the model's own uniqueness claims.
 */
@Component
public class CampaignRepetitionDetector {

    /** 1.0 = identical token sets, 0.0 = no overlap at all. Blank input on either side is always 0 (nothing to compare). */
    public BigDecimal similarity(String a, String b) {
        Set<String> tokensA = DeterministicTranscriptTokenizer.tokenSet(a);
        Set<String> tokensB = DeterministicTranscriptTokenizer.tokenSet(b);
        if (tokensA.isEmpty() || tokensB.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);
        if (intersection.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);
        return new BigDecimal(intersection.size())
                .divide(new BigDecimal(union.size()), MathContext.DECIMAL64)
                .setScale(4, RoundingMode.HALF_UP);
    }

    public boolean nearDuplicate(String a, String b, BigDecimal threshold) {
        return similarity(a, b).compareTo(threshold) >= 0;
    }
}
