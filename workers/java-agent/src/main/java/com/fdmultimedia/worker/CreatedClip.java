package com.fdmultimedia.worker;

import java.nio.file.Path;

record CreatedClip(
        Path path,
        long fileSizeBytes,
        String checksumSha256,
        String contentType,
        String containerFormat) {
}
