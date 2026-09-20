package com.fdmultimedia.api.experiments;

import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import com.fdmultimedia.api.personas.PersonaSnapshot;
import java.util.UUID;

/**
 * The frozen treatment a RobotRun's experimental assignment resolves to,
 * passed into {@code ContentSuggestionService} so AI generation uses the
 * Experiment variant's snapshot instead of resolving the live Persona.
 * {@code personaSnapshot} is never re-read from a live Persona row — it is
 * always {@link ExperimentVariant#toPersonaSnapshot()}.
 */
public record ExperimentTreatment(
        UUID experimentId,
        UUID experimentAssignmentId,
        UUID experimentVariantId,
        PersonaSnapshot personaSnapshot,
        SuggestionLanguage defaultLanguage,
        SuggestionTone defaultTone) {
}
