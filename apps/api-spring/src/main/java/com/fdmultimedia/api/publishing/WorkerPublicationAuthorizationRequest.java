package com.fdmultimedia.api.publishing;

import jakarta.validation.constraints.NotBlank;

public record WorkerPublicationAuthorizationRequest(@NotBlank String machineIdentifier) {
}
