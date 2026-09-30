package com.fdmultimedia.api.campaigns;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record WorkerCoordinatedCopyCompletionRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID copySetId,
        String seriesTitle,
        @Valid @NotNull List<WorkerCoordinatedCopyItemRequest> items) {
}
