package com.fdmultimedia.worker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Deterministic, non-real publishing provider used for Phase 10A. It never
 * contacts Instagram, TikTok, or any external platform. It only validates
 * that the media downloaded via the presigned URL is present, non-empty, and
 * (when provided) matches the expected checksum, then returns a
 * deterministic provider id derived from the Publication id so that retries
 * of the same Publication are idempotent.
 */
final class TestPublishingProvider implements PublishingProvider {

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String platform() {
        return "TEST";
    }

    @Override
    public PublishResult publish(Path mediaFile, PublicationAuthorization authorization)
            throws IOException, ImportFailureException {
        if (!"TEST".equals(authorization.platform())) {
            throw new ImportFailureException(
                    "PUBLISH_UNSUPPORTED_PLATFORM", "TEST provider cannot publish to " + authorization.platform(), true);
        }
        long size = Files.size(mediaFile);
        if (size <= 0) {
            throw new ImportFailureException("PUBLISH_MEDIA_EMPTY", "Downloaded media was empty", true);
        }
        String expectedChecksum = authorization.expectedChecksumSha256();
        if (expectedChecksum != null && !expectedChecksum.isBlank()) {
            String actualChecksum = sha256(mediaFile);
            if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                throw new ImportFailureException(
                        "PUBLISH_CHECKSUM_MISMATCH", "Downloaded media checksum did not match the expected checksum", true);
            }
        }
        String providerPublicationId = "test-pub-" + authorization.publicationId();
        String providerRequestId = "test-req-" + authorization.publicationId();
        return new PublishResult(providerRequestId, providerPublicationId, Instant.now());
    }

    private String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
