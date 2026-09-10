package com.fdmultimedia.worker;

import java.nio.file.Path;

record DownloadedMedia(
        Path path,
        String originalFilename,
        String contentType,
        long fileSizeBytes,
        String checksumSha256,
        String containerFormat) {
}
