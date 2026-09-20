package com.fdmultimedia.api.analytics;

import java.time.Instant;
import java.util.UUID;

public record PublicationAttribution(
        UUID publicationId, UUID contentDraftId, UUID publishScheduleId,
        UUID robotRunId, UUID robotId, String robotNameSnapshot,
        UUID contentSourceId, UUID sourceMediaAssetId, UUID finalMediaAssetId,
        UUID appliedContentSuggestionId, String suggestionOrigin,
        UUID personaId, String personaNameSnapshot, String aiProvider,
        String aiModel, String promptVersion, String aiPolicy,
        String robotAutonomyMode, String sourceSelectionPolicy, Instant createdAt) {
}
