package com.fdmultimedia.api.jobs;

import java.util.Map;
import java.util.UUID;

public record WorkerJobClaimResponse(
        boolean available,
        UUID jobId,
        JobType type,
        Map<String, Object> payload,
        int attemptCount,
        long leaseSeconds) {

    public static WorkerJobClaimResponse none() {
        return new WorkerJobClaimResponse(false, null, null, null, 0, 0);
    }
}
