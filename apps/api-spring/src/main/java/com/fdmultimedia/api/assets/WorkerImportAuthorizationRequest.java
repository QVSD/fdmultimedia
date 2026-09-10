package com.fdmultimedia.api.assets;

import jakarta.validation.constraints.NotBlank;

public record WorkerImportAuthorizationRequest(@NotBlank String machineIdentifier) {
}
