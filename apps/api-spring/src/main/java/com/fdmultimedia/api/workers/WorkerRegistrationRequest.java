package com.fdmultimedia.api.workers;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record WorkerRegistrationRequest(
        @NotBlank String machineIdentifier,
        @NotBlank String name,
        @NotBlank String operatingSystem,
        @NotBlank String architecture,
        @NotBlank String cpuModel,
        @Min(1) int cpuLogicalCores,
        @Min(1) long totalMemoryBytes,
        String gpuModel,
        @Min(1) Long gpuMemoryBytes,
        @NotBlank String agentVersion) {
}
