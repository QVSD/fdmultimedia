package com.fdmultimedia.api.experiments;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

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
        String variantBLabel) {
}
