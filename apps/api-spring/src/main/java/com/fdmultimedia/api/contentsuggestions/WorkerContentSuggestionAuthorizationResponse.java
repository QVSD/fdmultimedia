package com.fdmultimedia.api.contentsuggestions;

import java.util.UUID;

/**
 * Everything a Worker needs to invoke its provider — a fully-built,
 * bounded prompt frozen at generation time, plus the labels/bounds needed
 * to shape and self-check its own output before sending it back. Never
 * carries a provider secret; the Worker reads its own endpoint/API key from
 * its own environment.
 */
public record WorkerContentSuggestionAuthorizationResponse(
        UUID suggestionId,
        UUID contentDraftId,
        String provider,
        String model,
        String promptVersion,
        String prompt,
        SuggestionLanguage language,
        SuggestionTone tone,
        int maxHookLength,
        int maxCaptionLength,
        int maxHashtags,
        int maxHashtagLength,
        int maxShortTitleLength) {
}
