package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.experiments.ExperimentVariantKey;
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
        RobotAiPolicy aiPolicySnapshot,
        UUID personaIdSnapshot,
        String personaNameSnapshot,
        UUID contentSuggestionId,
        UUID experimentId,
        UUID experimentVariantId,
        ExperimentVariantKey experimentVariantKey,
        UUID publishScheduleId,
        String failureCode,
        String failureMessage,
        Instant createdAt) {
}
