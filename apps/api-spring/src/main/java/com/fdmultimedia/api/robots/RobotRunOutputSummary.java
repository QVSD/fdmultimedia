package com.fdmultimedia.api.robots;

import java.time.Instant;
import java.util.UUID;

public record RobotRunOutputSummary(
        UUID id,
        int selectionOrder,
        int sourceRank,
        UUID highlightCandidateId,
        long startMs,
        long endMs,
        String transcriptExcerpt,
        RobotRunOutputStatus status,
        UUID contentDraftId,
        UUID contentSuggestionId,
        UUID robotApprovalId,
        UUID publishScheduleId,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
}
