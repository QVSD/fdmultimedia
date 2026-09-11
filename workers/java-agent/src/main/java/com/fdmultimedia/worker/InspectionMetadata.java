package com.fdmultimedia.worker;

import java.math.BigDecimal;

record InspectionMetadata(
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
