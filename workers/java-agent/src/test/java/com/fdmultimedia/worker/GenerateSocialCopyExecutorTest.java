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

class GenerateSocialCopyExecutorTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SUGGESTION_ID = UUID.fromString("00000000-0000-4000-8000-000000000010");

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
        server.createContext("/api/worker-agent/content-suggestions/" + JOB_ID + "/authorization", exchange -> {
            byte[] response = authorizationJson("DETERMINISTIC_TEST").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/worker-agent/content-suggestions/" + JOB_ID + "/complete", exchange -> {
            completionBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        WorkerAgentClient client = new WorkerAgentClient(URI.create(baseUrl() + "/api/"), "WorkerToken credential.secret");
        GenerateSocialCopyExecutor executor = new GenerateSocialCopyExecutor(Map.of("DETERMINISTIC_TEST", new DeterministicSocialCopyProvider()));

        executor.execute(client, new ClaimedJob(true, JOB_ID, "GENERATE_SOCIAL_COPY", Map.of(), 1, 30), "machine-1");

        assertTrue(completionBody.get().contains(SUGGESTION_ID.toString()));
        assertTrue(completionBody.get().contains("\"hook\""));
    }

    @Test
    void unsupportedProviderFailsWithSafeCode() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/worker-agent/content-suggestions/" + JOB_ID + "/authorization", exchange -> {
            byte[] response = authorizationJson("OLLAMA").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        WorkerAgentClient client = new WorkerAgentClient(URI.create(baseUrl() + "/api/"), "WorkerToken credential.secret");
        GenerateSocialCopyExecutor executor = new GenerateSocialCopyExecutor(Map.of("DETERMINISTIC_TEST", new DeterministicSocialCopyProvider()));

        ImportFailureException failure = assertThrows(ImportFailureException.class, () ->
                executor.execute(client, new ClaimedJob(true, JOB_ID, "GENERATE_SOCIAL_COPY", Map.of(), 1, 30), "machine-1"));

        assertEquals("AI_PROVIDER_UNAVAILABLE", failure.code());
        assertTrue(failure.terminal());
    }

    private String authorizationJson(String provider) {
        return "{\"suggestionId\":\"" + SUGGESTION_ID + "\","
                + "\"contentDraftId\":\"" + UUID.randomUUID() + "\","
                + "\"provider\":\"" + provider + "\","
                + "\"model\":\"deterministic-v1\","
                + "\"promptVersion\":\"SOCIAL_COPY_V1\","
                + "\"prompt\":\"Source file: clip.mp4\\n\","
                + "\"language\":\"ENGLISH\","
                + "\"tone\":\"CASUAL\","
                + "\"maxHookLength\":200,"
                + "\"maxCaptionLength\":2200,"
                + "\"maxHashtags\":30,"
                + "\"maxHashtagLength\":50,"
                + "\"maxShortTitleLength\":100}";
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
