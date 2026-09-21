package com.fdmultimedia.api.experiments;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import java.math.BigDecimal;

/** DRAFT-only (item 36) — the factor itself is never editable, not even in DRAFT. */
public record UpdateExperimentRequest(
        @NotNull String name,
        String description,
        @NotNull String hypothesis,
        @NotNull String targetObservationWindow,
        @NotNull String primaryMetric,
        @NotNull UUID variantAPersonaId,
        String variantALabel,
        @NotNull UUID variantBPersonaId,
        String variantBLabel,
        BigDecimal minimumPracticalEffect) {
    public UpdateExperimentRequest(String name, String description, String hypothesis, String window,
            String metric, UUID personaA, String labelA, UUID personaB, String labelB) {
        this(name, description, hypothesis, window, metric, personaA, labelA, personaB, labelB, BigDecimal.ONE);
    }
}
