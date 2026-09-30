package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OllamaCoordinatedCopyProviderTest {

    private static final UUID OUTPUT_ID = UUID.fromString("00000000-0000-4000-8000-000000000030");

    @Test
    void parsesStructuredCoordinatedCopyResponse() throws Exception {
        String generated = "{\"seriesTitle\":\"Launch week\",\"items\":[{\"outputId\":\"" + OUTPUT_ID
                + "\",\"hook\":\"Big news\",\"caption\":\"Watch this.\",\"hashtags\":[\"ai\",\"tech\"],"
                + "\"shortTitle\":\"Quick\",\"continuityNote\":\"Part 1\"}]}";
        try (TestOllama server = TestOllama.startGenerate(200, "{\"response\":" + jsonQuote(generated) + "}")) {
            OllamaCoordinatedCopyProvider provider = new OllamaCoordinatedCopyProvider(server.uri(), "llama3.2:3b", Duration.ofSeconds(5));

            CoordinatedCopyResult result = provider.generate(authorization());

            assertEquals("Launch week", result.seriesTitle());
            assertEquals(1, result.items().size());
            CoordinatedCopyItemResult item = result.items().get(0);
            assertEquals(OUTPUT_ID, item.outputId());
            assertEquals("Big news", item.hook());
            assertEquals("Watch this.", item.caption());
            assertEquals(List.of("ai", "tech"), item.hashtags());
            assertEquals("Quick", item.shortTitle());
            assertEquals("Part 1", item.continuityNote());
        }
    }

    @Test
    void skipsItemsWithMalformedOutputIdAndLeavesBackendToReject() throws Exception {
        String generated = "{\"seriesTitle\":\"Launch week\",\"items\":[{\"outputId\":\"not-a-uuid\",\"hook\":\"x\",\"caption\":\"y\"}]}";
        try (TestOllama server = TestOllama.startGenerate(200, "{\"response\":" + jsonQuote(generated) + "}")) {
            OllamaCoordinatedCopyProvider provider = new OllamaCoordinatedCopyProvider(server.uri(), "llama3.2:3b", Duration.ofSeconds(5));

            CoordinatedCopyResult result = provider.generate(authorization());

            assertTrue(result.items().isEmpty());
        }
    }

    @Test
    void malformedProviderJsonIsTerminalFailure() throws Exception {
        try (TestOllama server = TestOllama.startGenerate(200, "{\"response\":\"not-json\"}")) {
            OllamaCoordinatedCopyProvider provider = new OllamaCoordinatedCopyProvider(server.uri(), "llama3.2:3b", Duration.ofSeconds(5));

            ImportFailureException failure = assertThrows(ImportFailureException.class, () -> provider.generate(authorization()));

            assertEquals("AI_INVALID_RESPONSE", failure.code());
            assertTrue(failure.terminal());
        }
    }

    @Test
    void rateLimitedResponseIsNonTerminal() throws Exception {
        try (TestOllama server = TestOllama.startGenerate(429, "{\"error\":\"slow down\"}")) {
            OllamaCoordinatedCopyProvider provider = new OllamaCoordinatedCopyProvider(server.uri(), "llama3.2:3b", Duration.ofSeconds(5));

            ImportFailureException failure = assertThrows(ImportFailureException.class, () -> provider.generate(authorization()));

            assertEquals("AI_RATE_LIMITED", failure.code());
            assertTrue(!failure.terminal());
        }
    }

    @Test
    void authenticationFailureIsTerminal() throws Exception {
        try (TestOllama server = TestOllama.startGenerate(401, "{\"error\":\"unauthorized\"}")) {
            OllamaCoordinatedCopyProvider provider = new OllamaCoordinatedCopyProvider(server.uri(), "llama3.2:3b", Duration.ofSeconds(5));

            ImportFailureException failure = assertThrows(ImportFailureException.class, () -> provider.generate(authorization()));

            assertEquals("AI_AUTHENTICATION_FAILED", failure.code());
            assertTrue(failure.terminal());
        }
    }

    @Test
    void connectionFailureMapsToProviderUnavailable() {
        OllamaCoordinatedCopyProvider provider = new OllamaCoordinatedCopyProvider(
                URI.create("http://127.0.0.1:1/"), "llama3.2:3b", Duration.ofSeconds(2));

        ImportFailureException failure = assertThrows(ImportFailureException.class, () -> provider.generate(authorization()));

        assertEquals("AI_PROVIDER_UNAVAILABLE", failure.code());
    }

    private String jsonQuote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private CoordinatedCopyAuthorization authorization() {
        return new CoordinatedCopyAuthorization(
                UUID.randomUUID(), UUID.randomUUID(), "OLLAMA", "llama3.2:3b", "SOCIAL_COPY_V4_COORDINATED",
                "prompt text", List.of(OUTPUT_ID), "ENGLISH", "CASUAL", 120, 200, 2200, 30, 50, 100, 200);
    }

    private record TestOllama(HttpServer server, URI uri) implements AutoCloseable {

        static TestOllama startGenerate(int status, String generateResponse) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/generate", exchange -> respond(exchange, status, generateResponse));
            server.start();
            return new TestOllama(server, uriFor(server));
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private static URI uriFor(HttpServer server) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
