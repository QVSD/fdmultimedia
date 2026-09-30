package com.fdmultimedia.api.contentsuggestions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Never includes {@code promptText} — prompt internals stay backend-only; only safe audit metadata is exposed. */
public record ContentSuggestionSummary(
        UUID id,
        UUID contentDraftId,
        ContentSuggestionOrigin origin,
        UUID robotRunId,
        UUID experimentId,
        UUID experimentAssignmentId,
        UUID experimentVariantId,
        ContentSuggestionType type,
        ContentSuggestionStatus status,
        String provider,
        String model,
        String promptVersion,
        SuggestionLanguage language,
        SuggestionTone tone,
        UUID personaId,
        String personaName,
        String hook,
        String caption,
        List<String> hashtags,
        String shortTitle,
        boolean transcriptUsed,
        UUID transcriptId,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long latencyMs,
        String failureCode,
        String failureMessage,
        boolean stale,
        Instant createdAt,
        Instant completedAt,
        Instant appliedAt,
        UUID appliedByUserId,
        UUID campaignPlanId,
        Integer campaignPlanRevision,
        UUID campaignPlanItemId,
        UUID campaignCopySetId,
        Integer campaignCopySetRevision,
        UUID campaignCopyItemId) {

    /** Backward-compatible overload predating Phase 17E's campaign fields. */
    public ContentSuggestionSummary(
            UUID id, UUID contentDraftId, ContentSuggestionOrigin origin, UUID robotRunId, UUID experimentId,
            UUID experimentAssignmentId, UUID experimentVariantId, ContentSuggestionType type, ContentSuggestionStatus status,
            String provider, String model, String promptVersion, SuggestionLanguage language, SuggestionTone tone,
            UUID personaId, String personaName, String hook, String caption, List<String> hashtags, String shortTitle,
            boolean transcriptUsed, UUID transcriptId, Integer promptTokens, Integer completionTokens, Integer totalTokens,
            Long latencyMs, String failureCode, String failureMessage, boolean stale, Instant createdAt, Instant completedAt,
            Instant appliedAt, UUID appliedByUserId) {
        this(id, contentDraftId, origin, robotRunId, experimentId, experimentAssignmentId, experimentVariantId, type,
                status, provider, model, promptVersion, language, tone, personaId, personaName, hook, caption, hashtags,
                shortTitle, transcriptUsed, transcriptId, promptTokens, completionTokens, totalTokens, latencyMs,
                failureCode, failureMessage, stale, createdAt, completedAt, appliedAt, appliedByUserId,
                null, null, null, null, null, null);
    }

    /** Backward-compatible overload predating Phase 17F's coordinated-copy fields. */
    public ContentSuggestionSummary(
            UUID id, UUID contentDraftId, ContentSuggestionOrigin origin, UUID robotRunId, UUID experimentId,
            UUID experimentAssignmentId, UUID experimentVariantId, ContentSuggestionType type, ContentSuggestionStatus status,
            String provider, String model, String promptVersion, SuggestionLanguage language, SuggestionTone tone,
            UUID personaId, String personaName, String hook, String caption, List<String> hashtags, String shortTitle,
            boolean transcriptUsed, UUID transcriptId, Integer promptTokens, Integer completionTokens, Integer totalTokens,
            Long latencyMs, String failureCode, String failureMessage, boolean stale, Instant createdAt, Instant completedAt,
            Instant appliedAt, UUID appliedByUserId, UUID campaignPlanId, Integer campaignPlanRevision, UUID campaignPlanItemId) {
        this(id, contentDraftId, origin, robotRunId, experimentId, experimentAssignmentId, experimentVariantId, type,
                status, provider, model, promptVersion, language, tone, personaId, personaName, hook, caption, hashtags,
                shortTitle, transcriptUsed, transcriptId, promptTokens, completionTokens, totalTokens, latencyMs,
                failureCode, failureMessage, stale, createdAt, completedAt, appliedAt, appliedByUserId,
                campaignPlanId, campaignPlanRevision, campaignPlanItemId, null, null, null);
    }
}
