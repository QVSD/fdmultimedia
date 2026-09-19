package com.fdmultimedia.api.contentsuggestions;

import jakarta.validation.constraints.NotNull;

/**
 * Deliberately only language/tone — no raw system prompt, no provider URL,
 * no model name from the frontend. Provider/model selection is server
 * configuration in Phase 12A.
 */
public record CreateContentSuggestionRequest(@NotNull SuggestionLanguage language, @NotNull SuggestionTone tone) {
}
