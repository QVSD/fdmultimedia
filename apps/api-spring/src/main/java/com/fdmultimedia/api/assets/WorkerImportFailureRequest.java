package com.fdmultimedia.api.assets;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkerImportFailureRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID assetId,
        @NotBlank String errorCode,
        @NotBlank String errorMessage,
        boolean terminal) {
}
