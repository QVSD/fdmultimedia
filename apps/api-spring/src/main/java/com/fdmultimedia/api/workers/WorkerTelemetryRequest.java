package com.fdmultimedia.api.workers;

public record WorkerTelemetryRequest(
        Double systemCpuLoad,
        Double processCpuLoad,
        Long availableMemoryBytes,
        Long jvmHeapUsedBytes,
        Long jvmHeapMaxBytes,
        Integer activeJobs) {
}
