package com.fdmultimedia.worker;

record WorkerTelemetry(
        Double systemCpuLoad,
        Double processCpuLoad,
        Long availableMemoryBytes,
        Long jvmHeapUsedBytes,
        Long jvmHeapMaxBytes,
        Integer activeJobs) {
}
