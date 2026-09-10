package com.fdmultimedia.api.assets;

import java.time.Instant;

public record StorageAccess(String url, String bucket, String key, Instant expiresAt) {
}
