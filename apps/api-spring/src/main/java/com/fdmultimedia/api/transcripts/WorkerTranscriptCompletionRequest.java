package com.fdmultimedia.api.transcripts;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record WorkerTranscriptCompletionRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID transcriptId,
        @NotNull UUID assetId,
        String detectedLanguage,
        Long durationMs,
        @NotNull List<WorkerTranscriptSegmentRequest> segments) {
}
