package com.fdmultimedia.api.jobs;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record WorkerJobClaimRequest(
        @NotBlank String machineIdentifier,
        List<JobType> supportedJobTypes) {
}
