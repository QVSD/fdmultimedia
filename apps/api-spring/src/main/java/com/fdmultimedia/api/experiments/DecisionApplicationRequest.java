package com.fdmultimedia.api.experiments;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record DecisionApplicationRequest(@NotNull UUID robotId, @NotBlank String previewFingerprint,
        @NotBlank @Size(max = 100) String idempotencyKey, boolean confirmOlderDecision,
        boolean confirmNotReadyDecision, boolean confirmActiveExperiment) {}
