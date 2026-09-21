package com.fdmultimedia.api.experiments;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DecisionRollbackRequest(@NotBlank String previewFingerprint,
        @NotBlank @Size(max = 100) String idempotencyKey) {}
