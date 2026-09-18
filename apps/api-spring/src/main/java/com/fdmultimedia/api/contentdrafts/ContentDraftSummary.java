package com.fdmultimedia.api.contentdrafts;

import com.fdmultimedia.api.publishing.PublicationSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ContentDraftSummary(
        UUID id,
        UUID sourceAssetId,
        UUID mediaAssetId,
        String mediaAssetFilename,
        UUID sourceHighlightCandidateId,
        String title,
        String caption,
        ContentDraftStatus status,
        ContentDraftWorkflowStage workflowStage,
        UUID pendingJobId,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        List<PublicationSummary> publications,
        UUID robotRunId) {
}
