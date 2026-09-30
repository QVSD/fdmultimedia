package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GenerateCoordinatedSocialCopyExecutorTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID COPY_SET_ID = UUID.fromString("00000000-0000-4000-8000-000000000020");
    private static final UUID OUTPUT_ID = UUID.fromString("00000000-0000-4000-8000-000000000030");

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void claimsWithDeterministicProviderAndCompletes() throws Exception {
        AtomicReference<String> completionBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/worker-agent/campaign-copy-sets/" + JOB_ID + "/authorization", exchange -> {
            byte[] response = authorizationJson("DETERMINISTIC_TEST").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/worker-agent/campaign-copy-sets/" + JOB_ID + "/complete", exchange -> {
            completionBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        WorkerAgentClient client = new WorkerAgentClient(URI.create(baseUrl() + "/api/"), "WorkerToken credential.secret");
        GenerateCoordinatedSocialCopyExecutor executor =
                new GenerateCoordinatedSocialCopyExecutor(Map.of("DETERMINISTIC_TEST", new DeterministicCoordinatedCopyProvider()));

        executor.execute(client, new ClaimedJob(true, JOB_ID, "GENERATE_COORDINATED_SOCIAL_COPY", Map.of(), 1, 30), "machine-1");

        assertTrue(completionBody.get().contains(COPY_SET_ID.toString()));
        assertTrue(completionBody.get().contains(OUTPUT_ID.toString()));
        assertTrue(completionBody.get().contains("\"seriesTitle\""));
    }

    @Test
    void unsupportedProviderFailsWithSafeCode() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/worker-agent/campaign-copy-sets/" + JOB_ID + "/authorization", exchange -> {
            byte[] response = authorizationJson("OLLAMA").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        WorkerAgentClient client = new WorkerAgentClient(URI.create(baseUrl() + "/api/"), "WorkerToken credential.secret");
        GenerateCoordinatedSocialCopyExecutor executor =
                new GenerateCoordinatedSocialCopyExecutor(Map.of("DETERMINISTIC_TEST", new DeterministicCoordinatedCopyProvider()));

        ImportFailureException failure = assertThrows(ImportFailureException.class, () ->
                executor.execute(client, new ClaimedJob(true, JOB_ID, "GENERATE_COORDINATED_SOCIAL_COPY", Map.of(), 1, 30), "machine-1"));

        assertEquals("AI_PROVIDER_UNAVAILABLE", failure.code());
        assertTrue(failure.terminal());
    }

    private String authorizationJson(String provider) {
        return "{\"copySetId\":\"" + COPY_SET_ID + "\","
                + "\"robotRunId\":\"" + UUID.randomUUID() + "\","
                + "\"provider\":\"" + provider + "\","
                + "\"model\":\"deterministic-v1\","
                + "\"promptVersion\":\"SOCIAL_COPY_V4_COORDINATED\","
                + "\"prompt\":\"Campaign plan section\\n\","
                + "\"expectedOutputIds\":[\"" + OUTPUT_ID + "\"],"
                + "\"language\":\"ENGLISH\","
                + "\"tone\":\"CASUAL\","
                + "\"maxSeriesTitleLength\":120,"
                + "\"maxHookLength\":200,"
                + "\"maxCaptionLength\":2200,"
                + "\"maxHashtags\":30,"
                + "\"maxHashtagLength\":50,"
                + "\"maxShortTitleLength\":100,"
                + "\"maxContinuityNoteLength\":200}";
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
