package com.fdmultimedia.api.assets;

import java.util.UUID;

/** Decoded, signature-verified contents of a public media delivery token. */
public record PublicMediaToken(UUID publicationId, UUID assetId, long expiresAtEpochSeconds) {
}
