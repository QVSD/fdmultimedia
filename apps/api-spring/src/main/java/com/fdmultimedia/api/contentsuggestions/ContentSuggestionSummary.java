package com.fdmultimedia.api.contentsuggestions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Never includes {@code promptText} — prompt internals stay backend-only; only safe audit metadata is exposed. */
public record ContentSuggestionSummary(
        UUID id,
        UUID contentDraftId,
        UUID robotRunId,
        ContentSuggestionType type,
        ContentSuggestionStatus status,
        String provider,
        String model,
        String promptVersion,
        SuggestionLanguage language,
        SuggestionTone tone,
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
        UUID appliedByUserId) {
}
