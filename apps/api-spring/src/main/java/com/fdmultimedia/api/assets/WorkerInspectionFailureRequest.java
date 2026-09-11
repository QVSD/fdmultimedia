package com.fdmultimedia.api.assets;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkerInspectionFailureRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID assetId,
        String errorCode,
        String errorMessage,
        Boolean terminal) {
}
