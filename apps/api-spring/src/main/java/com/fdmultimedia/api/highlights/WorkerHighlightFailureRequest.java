package com.fdmultimedia.api.highlights;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkerHighlightFailureRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID analysisId,
        @NotNull UUID assetId,
        String errorCode,
        String errorMessage,
        Boolean terminal) {
}
