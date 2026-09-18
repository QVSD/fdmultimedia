package com.fdmultimedia.api.jobs;

import java.util.List;

public record SchedulingOverview(
        String window,
        QueueMetrics queue,
        List<JobTypeMetrics> execution,
        SchedulingMetrics scheduling) {

    public record QueueMetrics(
            long queued,
            long assigned,
            long running,
            long succeeded,
            long failed,
            Long oldestQueuedAgeMs) {
    }

    public record JobTypeMetrics(
            String jobType,
            long attempts,
            long successes,
            long failures,
            Double averageQueueWaitMs,
            Double averageExecutionMs,
            Double averageTotalLatencyMs) {
    }

    public record SchedulingMetrics(long claims, long fallbackClaims, long starvationOverrideClaims) {
    }
}
