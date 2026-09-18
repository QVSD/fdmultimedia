package com.fdmultimedia.api.robots;

import java.time.Instant;
import java.util.UUID;

public record RobotRunSummary(
        UUID id,
        UUID robotId,
        String robotName,
        RobotRunTriggerType triggerType,
        RobotRunStatus status,
        Instant startedAt,
        Instant finishedAt,
        UUID sourceAssetId,
        UUID contentSourceId,
        String contentSourceName,
        RobotSelectionPolicy selectionPolicy,
        UUID highlightAnalysisId,
        UUID highlightCandidateId,
        UUID contentDraftId,
        UUID publishScheduleId,
        String failureCode,
        String failureMessage,
        Instant createdAt) {
}
