package com.fdmultimedia.api.workers;

import java.time.Instant;
import java.util.List;
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
        int maxActiveJobs,
        String schedulingPolicy,
        String schedulingState,
        List<String> supportedJobTypes,
        List<String> supportedHighlightAnalyzers,
        WorkerTelemetrySummary telemetry,
        Instant lastSeenAt,
        Instant registeredAt) {
}
