package com.fdmultimedia.api.workers;

import java.time.Instant;

public record WorkerTelemetrySummary(
        Double systemCpuLoad,
        Double processCpuLoad,
        Long availableMemoryBytes,
        Long jvmHeapUsedBytes,
        Long jvmHeapMaxBytes,
        Integer activeJobs,
        Instant lastTelemetryAt,
        boolean fresh) {
}
