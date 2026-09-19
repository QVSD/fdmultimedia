package com.fdmultimedia.api.contentsuggestions;

import java.util.UUID;

/**
 * Deliberately only language/tone/personaId — no raw system prompt, no
 * provider URL, no model name from the frontend. Provider/model selection
 * is server configuration in Phase 12A.
 *
 * <p>Phase 12B: {@code language}/{@code tone} are optional. Resolution
 * precedence (see {@code ContentSuggestionService.create}): an explicit
 * value here always wins; otherwise the selected Persona's default is used
 * when {@code personaId} is set; otherwise the Phase 12A default
 * (AUTO/NEUTRAL) applies. {@code personaId} is optional — omitting it
 * preserves full Phase 12A behavior.
 */
public record CreateContentSuggestionRequest(SuggestionLanguage language, SuggestionTone tone, UUID personaId) {

    /** Backward-compatible overload (no Persona) — Phase 12A call sites keep working unchanged. */
    public CreateContentSuggestionRequest(SuggestionLanguage language, SuggestionTone tone) {
        this(language, tone, null);
    }
}
