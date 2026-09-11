package com.fdmultimedia.api.assets;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MediaAssetSummary(
        UUID id,
        MediaAssetSourceType sourceType,
        String sourceUrl,
        MediaAssetStatus status,
        String originalFilename,
        String contentType,
        Long fileSizeBytes,
        String checksumSha256,
        Long durationMs,
        Integer width,
        Integer height,
        String videoCodec,
        String audioCodec,
        String containerFormat,
        UUID importJobId,
        MediaInspectionStatus inspectionStatus,
        UUID inspectionJobId,
        String inspectionErrorCode,
        String inspectionErrorMessage,
        BigDecimal frameRate,
        Long bitrate,
        Boolean hasVideo,
        Boolean hasAudio,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant readyAt) {
}
