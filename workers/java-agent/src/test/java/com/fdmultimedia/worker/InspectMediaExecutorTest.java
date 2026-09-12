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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InspectMediaExecutorTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void rejectsEarlyWhenContentLengthExceedsMaximum() throws Exception {
        byte[] media = "too-large".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asset", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, media.length);
            exchange.getResponseBody().write(media);
            exchange.close();
        });
        server.start();
        Path target = Files.createTempFile("fdm-inspect-test-", ".media");

        try {
            ImportFailureException ex = assertThrows(
                    ImportFailureException.class,
                    () -> executor().download(authorization("/asset", 3), target));

            assertEquals("ASSET_DOWNLOAD_TOO_LARGE", ex.code());
            assertTrue(ex.terminal());
            assertEquals(0, Files.size(target));
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void abortsOversizedInspectionDownloadWhileStreamingWithoutContentLength() throws Exception {
        byte[] chunk = "0123456789".repeat(100).getBytes(StandardCharsets.UTF_8);
        int totalBytes = chunk.length * 100;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asset", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                for (int i = 0; i < 100; i++) {
                    body.write(chunk);
                    body.flush();
                }
            } catch (Exception ignored) {
                // The client should close the stream as soon as the configured
                // maximum is exceeded; HttpServer surfaces that as an I/O error.
            }
        });
        server.start();
        Path target = Files.createTempFile("fdm-inspect-test-", ".media");

        try {
            ImportFailureException ex = assertThrows(
                    ImportFailureException.class,
                    () -> executor().download(authorization("/asset", 2_500), target));

            assertEquals("ASSET_DOWNLOAD_TOO_LARGE", ex.code());
            assertTrue(Files.size(target) < totalBytes);
            assertTrue(Files.size(target) <= 2_500);
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void executeCleansPartialTempFileAfterOversizedDownload() throws Exception {
        byte[] chunk = "0123456789".repeat(100).getBytes(StandardCharsets.UTF_8);
        UUID jobId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID assetId = UUID.fromString("00000000-0000-4000-8000-000000000010");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/worker-agent/assets/inspections/" + jobId + "/authorization", exchange -> {
            byte[] response = ("{\"assetId\":\"" + assetId + "\","
                    + "\"downloadUrl\":\"" + baseUrl() + "/asset\","
                    + "\"maxDownloadSizeBytes\":2500,"
                    + "\"connectTimeoutSeconds\":5,"
                    + "\"readTimeoutSeconds\":5}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/asset", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                for (int i = 0; i < 100; i++) {
                    body.write(chunk);
                    body.flush();
                }
            } catch (Exception ignored) {
                // Expected when the worker aborts the oversized download.
            }
        });
        server.start();
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long beforeCount = countInspectTemps(tmp);

        ImportFailureException ex = assertThrows(
                ImportFailureException.class,
                () -> executor().execute(
                        new WorkerAgentClient(URI.create(baseUrl() + "/api/"), "WorkerToken credential.secret"),
                        new ClaimedJob(true, jobId, "INSPECT_MEDIA", Map.of("assetId", assetId.toString()), 1, 30),
                        "machine-1"));

        assertEquals("ASSET_DOWNLOAD_TOO_LARGE", ex.code());
        assertEquals(beforeCount, countInspectTemps(tmp));
    }

    private InspectMediaExecutor executor() {
        return new InspectMediaExecutor(new FfprobeMediaInspector("ffprobe"));
    }

    private InspectionAuthorization authorization(String path, long maxBytes) {
        return new InspectionAuthorization(UUID.randomUUID(), baseUrl() + path, maxBytes, 5, 5);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private long countInspectTemps(Path tmp) throws Exception {
        try (var stream = Files.list(tmp)) {
            return stream
                    .filter(path -> path.getFileName().toString().startsWith("fdm-inspect-"))
                    .filter(path -> path.getFileName().toString().endsWith(".media"))
                    .count();
        }
    }

}
