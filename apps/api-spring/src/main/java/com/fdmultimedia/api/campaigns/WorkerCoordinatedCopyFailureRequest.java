package com.fdmultimedia.api.campaigns;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkerCoordinatedCopyFailureRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID copySetId,
        String errorCode,
        String errorMessage,
        Boolean terminal) {
}
