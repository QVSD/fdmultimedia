package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestPublishingProviderTest {

    private final TestPublishingProvider provider = new TestPublishingProvider();
    private Path media;

    @AfterEach
    void cleanup() throws Exception {
        if (media != null) {
            Files.deleteIfExists(media);
        }
    }

    @Test
    void isAlwaysAvailableAndNeverReal() {
        assertTrue(provider.isAvailable());
        assertEquals("TEST", provider.platform());
    }

    @Test
    void publishesDeterministicallyBasedOnPublicationId() throws Exception {
        media = writeMedia("hello world");
        UUID publicationId = UUID.randomUUID();
        PublicationAuthorization authorization = authorization(publicationId, null);

        PublishResult first = provider.publish(media, authorization);
        PublishResult second = provider.publish(media, authorization);

        assertEquals("test-pub-" + publicationId, first.providerPublicationId());
        assertEquals(first.providerPublicationId(), second.providerPublicationId());
        assertEquals(first.providerRequestId(), second.providerRequestId());
    }

    @Test
    void rejectsEmptyMedia() throws Exception {
        media = Files.createTempFile("fdm-publish-test-", ".media");
        PublicationAuthorization authorization = authorization(UUID.randomUUID(), null);

        ImportFailureException ex = assertThrows(ImportFailureException.class, () -> provider.publish(media, authorization));

        assertEquals("PUBLISH_MEDIA_EMPTY", ex.code());
        assertTrue(ex.terminal());
    }

    @Test
    void rejectsChecksumMismatch() throws Exception {
        media = writeMedia("hello world");
        PublicationAuthorization authorization = authorization(UUID.randomUUID(), "0".repeat(64));

        ImportFailureException ex = assertThrows(ImportFailureException.class, () -> provider.publish(media, authorization));

        assertEquals("PUBLISH_CHECKSUM_MISMATCH", ex.code());
        assertTrue(ex.terminal());
    }

    @Test
    void acceptsMatchingChecksum() throws Exception {
        media = writeMedia("hello world");
        String checksum = sha256("hello world");
        PublicationAuthorization authorization = authorization(UUID.randomUUID(), checksum);

        PublishResult result = provider.publish(media, authorization);

        assertTrue(result.providerPublicationId().startsWith("test-pub-"));
    }

    @Test
    void rejectsNonTestPlatform() throws Exception {
        media = writeMedia("hello world");
        PublicationAuthorization authorization = new PublicationAuthorization(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "INSTAGRAM", null,
                "http://example.test/media", null, 1_000, 5, 5, "idempotency-key");

        ImportFailureException ex = assertThrows(ImportFailureException.class, () -> provider.publish(media, authorization));

        assertEquals("PUBLISH_UNSUPPORTED_PLATFORM", ex.code());
        assertTrue(ex.terminal());
    }

    private PublicationAuthorization authorization(UUID publicationId, String expectedChecksum) {
        return new PublicationAuthorization(
                publicationId, UUID.randomUUID(), UUID.randomUUID(), "TEST", "caption",
                "http://example.test/media", expectedChecksum, 1_000, 5, 5, publicationId.toString());
    }

    private Path writeMedia(String content) throws Exception {
        Path file = Files.createTempFile("fdm-publish-test-", ".media");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
