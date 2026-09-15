package com.fdmultimedia.api.transcripts;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkerTranscriptFailureRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID transcriptId,
        @NotNull UUID assetId,
        String errorCode,
        String errorMessage,
        Boolean terminal) {
}
