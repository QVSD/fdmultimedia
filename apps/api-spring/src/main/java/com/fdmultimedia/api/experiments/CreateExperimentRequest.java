package com.fdmultimedia.api.experiments;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import java.math.BigDecimal;

public record CreateExperimentRequest(
        @NotNull String name,
        String description,
        @NotNull String hypothesis,
        ExperimentFactor factor,
        @NotNull String targetObservationWindow,
        @NotNull String primaryMetric,
        @NotNull UUID variantAPersonaId,
        String variantALabel,
        @NotNull UUID variantBPersonaId,
        String variantBLabel,
        BigDecimal minimumPracticalEffect) {
    public CreateExperimentRequest(String name, String description, String hypothesis, ExperimentFactor factor,
            String window, String metric, UUID personaA, String labelA, UUID personaB, String labelB) {
        this(name, description, hypothesis, factor, window, metric, personaA, labelA, personaB, labelB, BigDecimal.ONE);
    }
}
