package com.fdmultimedia.api.workers;

import jakarta.validation.constraints.NotBlank;

public record WorkerHeartbeatRequest(@NotBlank String machineIdentifier) {
}
