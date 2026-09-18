package com.fdmultimedia.api.contentsources;

import com.fdmultimedia.api.assets.MediaAssetStatus;
import com.fdmultimedia.api.assets.MediaDerivationType;
import com.fdmultimedia.api.assets.MediaInspectionStatus;
import java.time.Instant;
import java.util.UUID;

public record ContentSourceAssetSummary(
        UUID mediaAssetId,
        String originalFilename,
        MediaAssetStatus status,
        MediaInspectionStatus inspectionStatus,
        MediaDerivationType derivationType,
        Long durationMs,
        Boolean hasVideo,
        Instant addedAt) {
}
