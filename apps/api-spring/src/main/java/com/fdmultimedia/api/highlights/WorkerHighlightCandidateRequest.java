package com.fdmultimedia.api.highlights;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record WorkerHighlightCandidateRequest(
        @NotNull Long startMs,
        @NotNull Long endMs,
        @NotNull BigDecimal score,
        @NotBlank String reason) {
}
