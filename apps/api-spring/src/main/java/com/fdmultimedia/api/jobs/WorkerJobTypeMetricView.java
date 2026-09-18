package com.fdmultimedia.api.jobs;

import java.time.Instant;
import java.util.UUID;

public interface WorkerJobTypeMetricView extends JobTypeMetricView {
    UUID getWorkerId();
    String getWorkerName();
    Instant getMostRecentExecutionAt();
}
