package com.fdmultimedia.api.accounts;

import java.time.Instant;

/**
 * Safe, secret-free view of a stored credential's lifecycle state. Never
 * contains the token itself.
 */
public record SocialCredentialMetadata(
        boolean present,
        Instant tokenExpiresAt,
        boolean expired,
        String scopes,
        Instant lastValidatedAt) {

    public static SocialCredentialMetadata absent() {
        return new SocialCredentialMetadata(false, null, false, null, null);
    }
}
