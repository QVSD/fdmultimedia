package com.fdmultimedia.api.assets;

import com.fdmultimedia.api.accounts.crypto.SocialCredentialEncryptionProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Issues and validates short-lived, unguessable, publication-scoped tokens
 * for the unauthenticated {@code /api/public-media/{token}} endpoint that a
 * real provider (Meta) must be able to fetch video from. The token is an
 * HMAC-SHA256-signed, self-contained value — not a sequential database id —
 * so no token can be forged or enumerated, and it carries its own expiry so
 * no separate cleanup job is required.
 *
 * <p>Reuses the same master key as credential encryption
 * (SOCIAL_CREDENTIAL_ENCRYPTION_KEY) via a domain-separated HMAC, rather than
 * introducing a second key to configure — this is a MAC (integrity), not
 * AES-GCM (confidentiality), so there is no cross-protocol key reuse risk.
 */
@Service
public class PublicMediaTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte[] DOMAIN = "public-media-token:v1".getBytes(StandardCharsets.UTF_8);

    private final byte[] keyBytes;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public PublicMediaTokenService(SocialCredentialEncryptionProperties properties, Clock clock) {
        this.clock = clock;
        this.keyBytes = decodeKeyOrNull(properties.getEncryptionKey());
    }

    public boolean isAvailable() {
        return keyBytes != null;
    }

    public String issue(UUID publicationId, UUID assetId, Duration ttl) {
        requireAvailable();
        long expiresAt = Instant.now(clock).plus(ttl).getEpochSecond();
        byte[] nonce = new byte[8];
        secureRandom.nextBytes(nonce);
        String payload = publicationId + ":" + assetId + ":" + expiresAt + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(encodedPayload);
        return encodedPayload + "." + signature;
    }

    /** Returns the decoded token, or empty if the signature/format/expiry is invalid. Never throws on attacker input. */
    public java.util.Optional<PublicMediaToken> validate(String token) {
        if (!isAvailable() || token == null) {
            return java.util.Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot < 0) {
            return java.util.Optional.empty();
        }
        String encodedPayload = token.substring(0, dot);
        String providedSignature = token.substring(dot + 1);
        String expectedSignature = sign(encodedPayload);
        if (!constantTimeEquals(providedSignature, expectedSignature)) {
            return java.util.Optional.empty();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
            String[] parts = payload.split(":", 4);
            if (parts.length != 4) {
                return java.util.Optional.empty();
            }
            UUID publicationId = UUID.fromString(parts[0]);
            UUID assetId = UUID.fromString(parts[1]);
            long expiresAt = Long.parseLong(parts[2]);
            if (expiresAt < Instant.now(clock).getEpochSecond()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new PublicMediaToken(publicationId, assetId, expiresAt));
        } catch (RuntimeException malformed) {
            return java.util.Optional.empty();
        }
    }

    private String sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGORITHM));
            mac.update(DOMAIN);
            byte[] signature = mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC signing failed", ex);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private void requireAvailable() {
        if (keyBytes == null) {
            throw new IllegalStateException("SOCIAL_CREDENTIAL_ENCRYPTION_KEY is not configured");
        }
    }

    private static byte[] decodeKeyOrNull(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded.trim());
            return decoded.length == 32 ? decoded : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
