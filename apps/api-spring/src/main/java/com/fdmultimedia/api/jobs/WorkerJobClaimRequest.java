package com.fdmultimedia.api.jobs;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record WorkerJobClaimRequest(
        @NotBlank String machineIdentifier,
        List<JobType> supportedJobTypes,
        List<String> supportedHighlightAnalyzers) {

    public WorkerJobClaimRequest(String machineIdentifier, List<JobType> supportedJobTypes) {
        this(machineIdentifier, supportedJobTypes, null);
    }
}
