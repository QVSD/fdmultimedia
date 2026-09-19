package com.fdmultimedia.api.contentsuggestions;

/**
 * Generation guidance, not guaranteed source-language truth: AUTO instructs
 * the model to infer language from the supplied context (Draft text,
 * transcript excerpt), which is only ever as reliable as that context and
 * any upstream transcript language detection — Phase 7B1 acceptance already
 * showed Whisper-family detection can be imperfect. ENGLISH/ROMANIAN are
 * explicit overrides.
 */
public enum SuggestionLanguage {
    AUTO,
    ENGLISH,
    ROMANIAN
}
