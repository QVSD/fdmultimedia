package com.fdmultimedia.api.jobs;

import java.time.Instant;
import java.util.UUID;

public record WorkerPerformanceSummary(
        UUID workerId,
        String workerName,
        String jobType,
        long attempts,
        long successes,
        long failures,
        Double averageQueueWaitMs,
        Double averageExecutionMs,
        Double averageTotalLatencyMs,
        Instant mostRecentExecutionAt) {
}
