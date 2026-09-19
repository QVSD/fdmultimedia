package com.fdmultimedia.api.personas;

import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import java.time.Instant;
import java.util.UUID;

public record PersonaSummary(
        UUID id,
        String name,
        String description,
        PersonaStatus status,
        SuggestionLanguage defaultLanguage,
        SuggestionTone defaultTone,
        String audience,
        String voiceDescription,
        String styleGuidelines,
        String avoidGuidelines,
        String hashtagGuidelines,
        String exampleCopy,
        Instant createdAt,
        Instant updatedAt) {
}
