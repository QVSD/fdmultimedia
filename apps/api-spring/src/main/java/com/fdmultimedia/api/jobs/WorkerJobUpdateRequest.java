package com.fdmultimedia.api.jobs;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record WorkerJobUpdateRequest(
        @NotBlank String machineIdentifier,
        Map<String, Object> result,
        String errorCode,
        String errorMessage) {
}
