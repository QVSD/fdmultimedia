package com.fdmultimedia.api.jobs;

import com.fdmultimedia.api.workers.Worker;
import java.time.Instant;

public record JobExecutionSnapshot(
        Worker worker,
        int attempt,
        Instant queuedAt,
        Instant assignedAt,
        Instant startedAt) {

    public static JobExecutionSnapshot from(Job job) {
        return new JobExecutionSnapshot(
                job.getAssignedWorker(),
                job.getAttemptCount(),
                job.getQueuedAt(),
                job.getAssignedAt(),
                job.getStartedAt());
    }
}
