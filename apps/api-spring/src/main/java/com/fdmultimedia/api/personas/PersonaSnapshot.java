package com.fdmultimedia.api.personas;

import java.util.UUID;

/**
 * An immutable copy of the editorial fields of one {@link Persona} at the
 * instant a generation used it — never a live reference. A
 * {@code ContentSuggestion} stores exactly these fields as flat columns and
 * never re-reads the {@code Persona} row again, so later edits or archival
 * of that Persona can never change what an existing suggestion means.
 */
public record PersonaSnapshot(
        UUID personaId,
        String personaName,
        String audience,
        String voiceDescription,
        String styleGuidelines,
        String avoidGuidelines,
        String hashtagGuidelines,
        String exampleCopy) {
}
