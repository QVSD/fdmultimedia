package com.fdmultimedia.api.accounts;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe, browser-facing view of a {@link SocialAccount}. Never includes any
 * credential/token field — none exists on the entity to begin with.
 */
public record SocialAccountSummary(
        UUID id,
        SocialPlatform platform,
        String displayName,
        String externalAccountId,
        SocialAccountStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
