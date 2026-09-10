package com.fdmultimedia.api.jobs;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record JobCreateRequest(
        @NotNull JobType type,
        @NotNull Map<String, Object> payload) {
}
