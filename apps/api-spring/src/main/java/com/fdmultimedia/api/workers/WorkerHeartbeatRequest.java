package com.fdmultimedia.api.workers;

import com.fdmultimedia.api.jobs.JobType;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record WorkerHeartbeatRequest(
        @NotBlank String machineIdentifier,
        WorkerTelemetryRequest telemetry,
        List<JobType> supportedJobTypes,
        List<String> supportedHighlightAnalyzers,
        Integer maxActiveJobs) {

    public WorkerHeartbeatRequest(String machineIdentifier) {
        this(machineIdentifier, null, null, null, null);
    }

    public WorkerHeartbeatRequest(
            String machineIdentifier,
            WorkerTelemetryRequest telemetry,
            List<JobType> supportedJobTypes,
            List<String> supportedHighlightAnalyzers) {
        this(machineIdentifier, telemetry, supportedJobTypes, supportedHighlightAnalyzers, null);
    }
}
