package com.fdmultimedia.api.assets;

import java.time.Instant;

public record DownloadUrlResponse(String url, Instant expiresAt) {
}
