package com.fdmultimedia.api.assets;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkerImportCompletionRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID assetId,
        @NotBlank String storageBucket,
        @NotBlank String storageKey,
        String originalFilename,
        @NotBlank String contentType,
        long fileSizeBytes,
        @NotBlank String checksumSha256,
        Long durationMs,
        Integer width,
        Integer height,
        String videoCodec,
        String audioCodec,
        String containerFormat) {

    MediaImportMetadata metadata() {
        return new MediaImportMetadata(
                originalFilename,
                contentType,
                fileSizeBytes,
                checksumSha256,
                durationMs,
                width,
                height,
                videoCodec,
                audioCodec,
                containerFormat);
    }
}
