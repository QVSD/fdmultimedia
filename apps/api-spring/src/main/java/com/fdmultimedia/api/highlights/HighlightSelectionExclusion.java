package com.fdmultimedia.api.highlights;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "highlight_selection_exclusions")
public class HighlightSelectionExclusion {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "selection_id") private HighlightSelection selection;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "highlight_candidate_id") private HighlightCandidate candidate;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private HighlightSelectionExclusionReason reason;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "conflicting_candidate_id") private HighlightCandidate conflictingCandidate;
    @Column(name = "temporal_overlap_ratio") private BigDecimal temporalOverlapRatio;
    @Column(name = "lexical_similarity") private BigDecimal lexicalSimilarity;

    protected HighlightSelectionExclusion() {}
    public HighlightSelectionExclusion(HighlightSelection selection, HighlightCandidate candidate,
            HighlightSelectionExclusionReason reason, HighlightCandidate conflictingCandidate,
            BigDecimal temporalOverlapRatio, BigDecimal lexicalSimilarity) {
        this.id = UUID.randomUUID(); this.selection = selection; this.candidate = candidate; this.reason = reason;
        this.conflictingCandidate = conflictingCandidate; this.temporalOverlapRatio = temporalOverlapRatio;
        this.lexicalSimilarity = lexicalSimilarity;
    }
    public UUID getId() { return id; }
    public HighlightSelection getSelection() { return selection; }
    public HighlightCandidate getCandidate() { return candidate; }
    public HighlightSelectionExclusionReason getReason() { return reason; }
    public HighlightCandidate getConflictingCandidate() { return conflictingCandidate; }
    public BigDecimal getTemporalOverlapRatio() { return temporalOverlapRatio; }
    public BigDecimal getLexicalSimilarity() { return lexicalSimilarity; }
}
