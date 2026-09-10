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
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ImportMediaExecutorTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamsDownloadToFileAndCalculatesChecksum() throws Exception {
        byte[] media = "fake-media-bytes".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media.mp4", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, media.length);
            exchange.getResponseBody().write(media);
            exchange.close();
        });
        server.start();
        Path target = Files.createTempFile("fdm-import-test-", ".media");
        Files.deleteIfExists(target);

        try {
            DownloadedMedia downloaded = executor().download(authorization("/media.mp4", 100), target);

            assertEquals("media.mp4", downloaded.originalFilename());
            assertEquals("video/mp4", downloaded.contentType());
            assertEquals(media.length, downloaded.fileSizeBytes());
            assertEquals(sha256(media), downloaded.checksumSha256());
            assertEquals("fake-media-bytes", Files.readString(target));
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void enforcesMaximumSizeWhileStreaming() throws Exception {
        byte[] media = "too-large".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media.mp4", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, media.length);
            exchange.getResponseBody().write(media);
            exchange.close();
        });
        server.start();
        Path target = Files.createTempFile("fdm-import-test-", ".media");

        try {
            ImportFailureException ex = assertThrows(
                    ImportFailureException.class,
                    () -> executor().download(authorization("/media.mp4", 3), target));
            assertEquals("MEDIA_TOO_LARGE", ex.code());
            assertTrue(ex.terminal());
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void rejectsNonMediaContent() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/error.json", exchange -> {
            byte[] body = "{\"error\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path target = Files.createTempFile("fdm-import-test-", ".media");

        try {
            ImportFailureException ex = assertThrows(
                    ImportFailureException.class,
                    () -> executor().download(authorization("/error.json", 100), target));
            assertEquals("UNSUPPORTED_MEDIA", ex.code());
            assertTrue(ex.terminal());
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void revalidatesRedirectsAndFollowsBoundedRedirects() throws Exception {
        byte[] media = "redirected".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> finalPath = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/media.mp4");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/media.mp4", exchange -> {
            finalPath.set(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, media.length);
            exchange.getResponseBody().write(media);
            exchange.close();
        });
        server.start();
        Path target = Files.createTempFile("fdm-import-test-", ".media");

        try {
            DownloadedMedia downloaded = executor().download(authorization("/redirect", 100), target);

            assertEquals("/media.mp4", finalPath.get());
            assertEquals("media.mp4", downloaded.originalFilename());
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void executeUploadsCompletesAndDeletesTemporaryFile() throws Exception {
        byte[] media = "uploaded".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> uploaded = new AtomicReference<>();
        AtomicReference<String> completionBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/worker-agent/assets/imports/00000000-0000-4000-8000-000000000001/authorization", exchange -> {
            byte[] response = ("{\"assetId\":\"00000000-0000-4000-8000-000000000010\","
                    + "\"sourceUrl\":\"" + baseUrl() + "/media.mp4\","
                    + "\"uploadUrl\":\"" + baseUrl() + "/upload\","
                    + "\"storageBucket\":\"media-assets\","
                    + "\"storageKey\":\"workspaces/ws/assets/asset/original\","
                    + "\"maxDownloadSizeBytes\":100,"
                    + "\"connectTimeoutSeconds\":5,"
                    + "\"readTimeoutSeconds\":5,"
                    + "\"maxRedirects\":1}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/media.mp4", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, media.length);
            exchange.getResponseBody().write(media);
            exchange.close();
        });
        server.createContext("/upload", exchange -> {
            uploaded.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/api/worker-agent/assets/imports/00000000-0000-4000-8000-000000000001/complete", exchange -> {
            completionBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        WorkerAgentClient client = new WorkerAgentClient(URI.create(baseUrl() + "/api/"), "WorkerToken credential.secret");
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long beforeCount = countImportTemps(tmp);

        executor().execute(
                client,
                new ClaimedJob(true, UUID.fromString("00000000-0000-4000-8000-000000000001"), "IMPORT_MEDIA", Map.of(), 1, 30),
                "machine-1");

        assertEquals("uploaded", uploaded.get());
        assertTrue(completionBody.get().contains("\"checksumSha256\":\"" + sha256(media) + "\""));
        assertTrue(completionBody.get().contains("\"storageBucket\":\"media-assets\""));
        assertEquals(beforeCount, countImportTemps(tmp));
    }

    private ImportMediaExecutor executor() {
        return new ImportMediaExecutor(URI::create);
    }

    private ImportMediaAuthorization authorization(String path, long maxBytes) {
        return new ImportMediaAuthorization(
                UUID.randomUUID(),
                baseUrl() + path,
                baseUrl() + "/upload",
                "media-assets",
                "key",
                maxBytes,
                5,
                5,
                2);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private long countImportTemps(Path tmp) throws Exception {
        try (var stream = Files.list(tmp)) {
            return stream
                    .filter(path -> path.getFileName().toString().startsWith("fdm-import-"))
                    .filter(path -> path.getFileName().toString().endsWith(".media"))
                    .count();
        }
    }
}
