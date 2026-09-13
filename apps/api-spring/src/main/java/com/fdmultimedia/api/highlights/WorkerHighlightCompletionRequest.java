package com.fdmultimedia.api.highlights;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record WorkerHighlightCompletionRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID analysisId,
        @NotNull UUID assetId,
        @Valid @NotNull List<WorkerHighlightCandidateRequest> candidates) {
}
