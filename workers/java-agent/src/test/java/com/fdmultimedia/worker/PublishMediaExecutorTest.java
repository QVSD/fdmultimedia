package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PublishMediaExecutorTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void executePublishesToTestProviderAndReportsCompletion() throws Exception {
        byte[] media = "fake-media-bytes".getBytes(StandardCharsets.UTF_8);
        UUID publicationId = UUID.fromString("00000000-0000-4000-8000-000000000010");
        AtomicReference<String> completionBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/worker-agent/publications/00000000-0000-4000-8000-000000000001/authorization", exchange -> {
            byte[] response = authorizationJson(publicationId).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/media", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, media.length);
            exchange.getResponseBody().write(media);
            exchange.close();
        });
        server.createContext("/api/worker-agent/publications/00000000-0000-4000-8000-000000000001/complete", exchange -> {
            completionBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        WorkerAgentClient client = new WorkerAgentClient(URI.create(baseUrl() + "/api/"), "WorkerToken credential.secret");
        PublishMediaExecutor executor = new PublishMediaExecutor(new TestPublishingProvider());
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long before = countPublishTemps(tmp);

        executor.execute(
                client,
                new ClaimedJob(true, UUID.fromString("00000000-0000-4000-8000-000000000001"), "PUBLISH_MEDIA", java.util.Map.of(), 1, 30),
                "machine-1");

        assertTrue(completionBody.get().contains("\"providerPublicationId\":\"test-pub-" + publicationId + "\""));
        assertTrue(completionBody.get().contains("\"publicationId\":\"" + publicationId + "\""));
        assertEquals(before, countPublishTemps(tmp));
    }

    @Test
    void enforcesMaximumDownloadSizeWhileStreaming() throws Exception {
        byte[] media = "too-large-media-blob".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, media.length);
            exchange.getResponseBody().write(media);
            exchange.close();
        });
        server.start();
        Path target = Files.createTempFile("fdm-publish-media-", ".media");

        try {
            PublishMediaExecutor executor = new PublishMediaExecutor(new TestPublishingProvider());
            ImportFailureException ex = assertThrows(
                    ImportFailureException.class,
                    () -> invokeDownload(executor, authorization(UUID.randomUUID(), 3), target));

            assertEquals("PUBLISH_SOURCE_TOO_LARGE", ex.code());
            assertTrue(ex.terminal());
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void rejectsEmptyDownload() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/empty", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        Path target = Files.createTempFile("fdm-publish-media-", ".media");

        try {
            PublishMediaExecutor executor = new PublishMediaExecutor(new TestPublishingProvider());
            ImportFailureException ex = assertThrows(
                    ImportFailureException.class,
                    () -> invokeDownload(executor, authorizationForUrl(baseUrl() + "/empty", UUID.randomUUID(), 1_000), target));

            assertEquals("PUBLISH_SOURCE_EMPTY", ex.code());
        } finally {
            Files.deleteIfExists(target);
        }
    }

    private void invokeDownload(PublishMediaExecutor executor, PublicationAuthorization authorization, Path target) throws Exception {
        var method = PublishMediaExecutor.class.getDeclaredMethod("download", PublicationAuthorization.class, Path.class);
        method.setAccessible(true);
        try {
            method.invoke(executor, authorization, target);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            if (ex.getCause() instanceof ImportFailureException failure) {
                throw failure;
            }
            throw ex;
        }
    }

    private PublicationAuthorization authorization(UUID publicationId, long maxBytes) {
        return authorizationForUrl(baseUrl() + "/media", publicationId, maxBytes);
    }

    private PublicationAuthorization authorizationForUrl(String downloadUrl, UUID publicationId, long maxBytes) {
        return new PublicationAuthorization(
                publicationId, UUID.randomUUID(), UUID.randomUUID(), "TEST", "caption",
                downloadUrl, null, maxBytes, 5, 5, publicationId.toString());
    }

    private String authorizationJson(UUID publicationId) {
        return "{\"publicationId\":\"" + publicationId + "\","
                + "\"assetId\":\"00000000-0000-4000-8000-000000000020\","
                + "\"socialAccountId\":\"00000000-0000-4000-8000-000000000030\","
                + "\"platform\":\"TEST\","
                + "\"caption\":\"Hello\","
                + "\"downloadUrl\":\"" + baseUrl() + "/media\","
                + "\"expectedChecksumSha256\":null,"
                + "\"maxDownloadSizeBytes\":1000,"
                + "\"connectTimeoutSeconds\":5,"
                + "\"readTimeoutSeconds\":5,"
                + "\"idempotencyKey\":\"" + publicationId + "\"}";
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private long countPublishTemps(Path tmp) throws Exception {
        try (var stream = Files.list(tmp)) {
            return stream
                    .filter(path -> path.getFileName().toString().startsWith("fdm-publish-media-"))
                    .filter(path -> path.getFileName().toString().endsWith(".media"))
                    .count();
        }
    }
}
