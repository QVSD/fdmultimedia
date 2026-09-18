package com.fdmultimedia.api.robots;

import java.time.Instant;
import java.util.UUID;

public record RobotApprovalSummary(
        UUID id,
        UUID robotRunId,
        UUID robotId,
        String robotName,
        UUID contentDraftId,
        String draftTitle,
        String draftCaption,
        UUID socialAccountId,
        String socialAccountDisplayName,
        Instant proposedScheduledFor,
        RobotApprovalStatus status,
        Instant createdAt,
        Instant decidedAt,
        UUID decidedByUserId,
        UUID publishScheduleId) {
}
