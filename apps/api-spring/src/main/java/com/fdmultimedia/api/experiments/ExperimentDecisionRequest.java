package com.fdmultimedia.api.experiments;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ExperimentDecisionRequest(@NotNull String decision, String selectedVariantKey,
        @NotNull AnalysisPopulation population, @NotNull String rationale, @NotNull UUID idempotencyKey) {}
