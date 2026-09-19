package com.fdmultimedia.api.personas;

import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code defaultLanguage}/{@code defaultTone} are optional here — the
 * service defaults them (AUTO/NEUTRAL) when omitted; every other bound is
 * enforced by {@code PersonaService}. No raw prompt field exists: Persona
 * fields are structured editorial data, never a system prompt.
 */
public record CreatePersonaRequest(
        @NotBlank String name,
        String description,
        SuggestionLanguage defaultLanguage,
        SuggestionTone defaultTone,
        String audience,
        @NotBlank String voiceDescription,
        String styleGuidelines,
        String avoidGuidelines,
        String hashtagGuidelines,
        String exampleCopy) {
}
