package com.fdmultimedia.api.highlights;

import com.fdmultimedia.api.assets.MediaAssetStatus;
import com.fdmultimedia.api.jobs.JobStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HighlightSelectionSummary(
        UUID id, UUID mediaAssetId, UUID highlightAnalysisId, String selectorVersion,
        int requestedCount, int selectedCount, HighlightSelectionStatus status, Instant createdAt,
        List<Item> items, List<Exclusion> exclusions) {
    public record Item(
            UUID id, UUID candidateId, int selectionOrder, int sourceRank, long startMs, long endMs,
            BigDecimal score, String transcriptExcerpt, UUID clipAssetId, MediaAssetStatus clipAssetStatus,
            UUID clipJobId, JobStatus clipJobStatus, String clipFailureCode, String clipFailureMessage) {}
    public record Exclusion(
            UUID candidateId, int sourceRank, HighlightSelectionExclusionReason reason,
            UUID conflictingCandidateId, Integer conflictingSourceRank,
            BigDecimal temporalOverlapRatio, BigDecimal lexicalSimilarity) {}
}
