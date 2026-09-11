package com.fdmultimedia.api.assets;

import java.math.BigDecimal;

public record MediaInspectionMetadata(
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
}
