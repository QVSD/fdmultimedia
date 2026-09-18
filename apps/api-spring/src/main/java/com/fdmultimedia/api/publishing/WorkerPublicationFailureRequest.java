package com.fdmultimedia.api.publishing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkerPublicationFailureRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID publicationId,
        @NotNull UUID assetId,
        @NotNull UUID socialAccountId,
        String errorCode,
        String errorMessage,
        Boolean terminal) {
}
