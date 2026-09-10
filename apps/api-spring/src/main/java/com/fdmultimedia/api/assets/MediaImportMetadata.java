package com.fdmultimedia.api.assets;

public record MediaImportMetadata(
        String originalFilename,
        String contentType,
        long fileSizeBytes,
        String checksumSha256,
        Long durationMs,
        Integer width,
        Integer height,
        String videoCodec,
        String audioCodec,
        String containerFormat) {
}
