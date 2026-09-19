package com.fdmultimedia.api.personas;

import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import jakarta.validation.constraints.NotBlank;

public record UpdatePersonaRequest(
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
