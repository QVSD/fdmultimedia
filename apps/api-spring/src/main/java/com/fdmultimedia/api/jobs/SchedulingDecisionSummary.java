package com.fdmultimedia.api.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SchedulingDecisionSummary(
        Instant timestamp,
        UUID jobId,
        JobType jobType,
        UUID workerId,
        String workerName,
        String policy,
        Double suitabilityScore,
        boolean telemetryFresh,
        boolean fallbackUsed,
        boolean starvationOverride,
        List<String> reasonCodes,
        Integer activeJobs,
        int maxActiveJobs,
        int attempt) {
}
