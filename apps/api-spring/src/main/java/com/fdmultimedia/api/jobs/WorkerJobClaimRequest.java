package com.fdmultimedia.api.jobs;

import jakarta.validation.constraints.NotBlank;

public record WorkerJobClaimRequest(@NotBlank String machineIdentifier) {
}
