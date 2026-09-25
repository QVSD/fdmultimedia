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
        String robotAutonomyMode, String sourceSelectionPolicy,
        String contentSourceNameSnapshot,
        UUID experimentId, String experimentNameSnapshot, String experimentFactor,
        UUID experimentVariantId, String experimentVariantKey, String experimentVariantLabelSnapshot,
        UUID experimentFactorValueId, String experimentFactorValueNameSnapshot, UUID experimentAssignmentId,
        Boolean protocolDeviation, String protocolDeviationReason,
        UUID robotRunOutputId, UUID highlightSelectionId, UUID highlightSelectionItemId,
        UUID highlightCandidateId, Integer selectionOrder, Integer sourceRank,
        UUID campaignPlanId, Integer campaignPlanRevision, UUID campaignPlanItemId,
        Instant createdAt) {
}
