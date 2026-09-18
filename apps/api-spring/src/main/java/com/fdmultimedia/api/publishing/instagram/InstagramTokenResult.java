package com.fdmultimedia.api.publishing.instagram;

import java.time.Instant;

public record InstagramTokenResult(String accessToken, Instant expiresAt) {
}
