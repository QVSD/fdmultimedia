package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OllamaContentEnrichmentProviderTest {

    @Test
    void reportsAvailableWhenConfiguredModelIsInstalled() throws Exception {
        try (TestOllama server = TestOllama.startTags(200, "{\"models\":[{\"name\":\"llama3.2:3b\"}]}")) {
            OllamaContentEnrichmentProvider provider = new OllamaContentEnrichmentProvider(server.uri(), "llama3.2:3b", Duration.ofSeconds(5));

            assertTrue(provider.isAvailable());
        }
    }

    @Test
    void parsesStructuredSocialCopyResponse() throws Exception {
        try (TestOllama server = TestOllama.startGenerate(200,
                "{\"response\":\"{\\\"hook\\\":\\\"Big news\\\",\\\"caption\\\":\\\"Watch this.\\\",\\\"hashtags\\\":[\\\"ai\\\",\\\"tech\\\"],\\\"shortTitle\\\":\\\"Quick\\\"}\"}")) {
            OllamaContentEnrichmentProvider provider = new OllamaContentEnrichmentProvider(server.uri(), "llama3.2:3b", Duration.ofSeconds(5));

            SocialCopyResult result = provider.generate(authorization());

            assertEquals("Big news", result.hook());
            assertEquals("Watch this.", result.caption());
            assertEquals(java.util.List.of("ai", "tech"), result.hashtags());
            assertEquals("Quick", result.shortTitle());
            assertTrue(result.latencyMs() >= 0);
        }
    }

    @Test
    void malformedProviderJsonIsTerminalFailure() throws Exception {
        try (TestOllama server = TestOllama.startGenerate(200, "{\"response\":\"not-json\"}")) {
            OllamaContentEnrichmentProvider provider = new OllamaContentEnrichmentProvider(server.uri(), "llama3.2:3b", Duration.ofSeconds(5));

            ImportFailureException failure = assertThrows(ImportFailureException.class, () -> provider.generate(authorization()));

            assertEquals("AI_INVALID_RESPONSE", failure.code());
            assertTrue(failure.terminal());
        }
    }

    @Test
    void rateLimitedResponseIsNonTerminal() throws Exception {
        try (TestOllama server = TestOllama.startGenerate(429, "{\"error\":\"slow down\"}")) {
            OllamaContentEnrichmentProvider provider = new OllamaContentEnrichmentProvider(server.uri(), "llama3.2:3b", Duration.ofSeconds(5));

            ImportFailureException failure = assertThrows(ImportFailureException.class, () -> provider.generate(authorization()));

            assertEquals("AI_RATE_LIMITED", failure.code());
            assertTrue(!failure.terminal());
        }
    }

    @Test
    void authenticationFailureIsTerminal() throws Exception {
        try (TestOllama server = TestOllama.startGenerate(401, "{\"error\":\"unauthorized\"}")) {
            OllamaContentEnrichmentProvider provider = new OllamaContentEnrichmentProvider(server.uri(), "llama3.2:3b", Duration.ofSeconds(5));

            ImportFailureException failure = assertThrows(ImportFailureException.class, () -> provider.generate(authorization()));

            assertEquals("AI_AUTHENTICATION_FAILED", failure.code());
            assertTrue(failure.terminal());
        }
    }

    @Test
    void connectionFailureMapsToProviderUnavailable() {
        OllamaContentEnrichmentProvider provider = new OllamaContentEnrichmentProvider(
                URI.create("http://127.0.0.1:1/"), "llama3.2:3b", Duration.ofSeconds(2));

        ImportFailureException failure = assertThrows(ImportFailureException.class, () -> provider.generate(authorization()));

        assertEquals("AI_PROVIDER_UNAVAILABLE", failure.code());
    }

    private SocialCopyAuthorization authorization() {
        return new SocialCopyAuthorization(
                UUID.randomUUID(), UUID.randomUUID(), "OLLAMA", "llama3.2:3b", "SOCIAL_COPY_V1",
                "prompt text", "ENGLISH", "CASUAL", 200, 2200, 30, 50, 100);
    }

    private record TestOllama(HttpServer server, URI uri) implements AutoCloseable {

        static TestOllama startTags(int status, String tagsResponse) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/tags", exchange -> respond(exchange, status, tagsResponse));
            server.start();
            return new TestOllama(server, uriFor(server));
        }

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
