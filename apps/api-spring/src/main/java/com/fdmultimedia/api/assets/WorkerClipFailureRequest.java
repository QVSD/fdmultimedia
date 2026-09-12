package com.fdmultimedia.api.assets;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkerClipFailureRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID sourceAssetId,
        @NotNull UUID outputAssetId,
        @NotBlank String errorCode,
        String errorMessage,
        Boolean terminal) {
}
