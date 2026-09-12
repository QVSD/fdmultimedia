package com.fdmultimedia.api.assets;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkerClipCompletionRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID sourceAssetId,
        @NotNull UUID outputAssetId,
        @NotBlank String checksumSha256,
        long fileSizeBytes,
        String originalFilename,
        String contentType,
        String containerFormat) {

    MediaImportMetadata metadata() {
        return new MediaImportMetadata(
                originalFilename == null || originalFilename.isBlank() ? "derivative.mp4" : originalFilename,
                contentType == null || contentType.isBlank() ? "video/mp4" : contentType,
                fileSizeBytes,
                checksumSha256,
                null,
                null,
                null,
                null,
                null,
                containerFormat == null || containerFormat.isBlank() ? "mp4" : containerFormat);
    }
}
