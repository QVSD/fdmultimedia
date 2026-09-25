package com.fdmultimedia.api.highlights;

public enum HighlightSelectionExclusionReason {
    TEMPORAL_OVERLAP,
    INSUFFICIENT_TEMPORAL_GAP,
    LEXICAL_DUPLICATE,
    BELOW_QUALITY_FLOOR,
    SELECTION_LIMIT_REACHED
}
