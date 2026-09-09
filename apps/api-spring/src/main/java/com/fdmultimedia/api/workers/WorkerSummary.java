package com.fdmultimedia.api.workers;

import java.time.Instant;
import java.util.UUID;

public record WorkerSummary(
        UUID id,
        String name,
        WorkerStatus status,
        String machineIdentifier,
        String operatingSystem,
        String architecture,
        String cpuModel,
        int cpuLogicalCores,
        long totalMemoryBytes,
        String gpuModel,
        Long gpuMemoryBytes,
        String agentVersion,
        Instant lastSeenAt,
        Instant registeredAt) {
}
