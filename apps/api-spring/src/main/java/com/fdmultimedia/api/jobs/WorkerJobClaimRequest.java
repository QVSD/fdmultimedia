package com.fdmultimedia.api.jobs;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record WorkerJobClaimRequest(
        @NotBlank String machineIdentifier,
        List<JobType> supportedJobTypes,
        List<String> supportedHighlightAnalyzers,
        List<String> supportedPublishingProviders) {

    public WorkerJobClaimRequest(String machineIdentifier, List<JobType> supportedJobTypes) {
        this(machineIdentifier, supportedJobTypes, null, null);
    }

    public WorkerJobClaimRequest(String machineIdentifier, List<JobType> supportedJobTypes, List<String> supportedHighlightAnalyzers) {
        this(machineIdentifier, supportedJobTypes, supportedHighlightAnalyzers, null);
    }
}
