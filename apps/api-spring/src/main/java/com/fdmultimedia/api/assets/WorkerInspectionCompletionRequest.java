package com.fdmultimedia.api.assets;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record WorkerInspectionCompletionRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID assetId,
        Long durationMs,
        Integer width,
        Integer height,
        String videoCodec,
        String audioCodec,
        String containerFormat,
        BigDecimal frameRate,
        Long bitrate,
        Boolean hasVideo,
        Boolean hasAudio) {

    MediaInspectionMetadata metadata() {
        return new MediaInspectionMetadata(
                durationMs,
                width,
                height,
                videoCodec,
                audioCodec,
                containerFormat,
                frameRate,
                bitrate,
                hasVideo,
                hasAudio);
    }
}
