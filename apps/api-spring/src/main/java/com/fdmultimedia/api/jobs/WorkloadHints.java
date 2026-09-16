package com.fdmultimedia.api.jobs;

public record WorkloadHints(
        Long sizeBytes,
        Long durationMs,
        Integer width,
        Integer height,
        String provider,
        String model,
        String analyzerType) {

    public static WorkloadHints empty() {
        return new WorkloadHints(null, null, null, null, null, null, null);
    }
}
