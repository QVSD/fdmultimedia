package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OllamaSemanticHighlightAnalyzerTest {

    @Test
    void reportsAvailableWhenConfiguredModelIsInstalled() throws Exception {
        try (TestOllama server = TestOllama.start(
                "{\"models\":[{\"name\":\"llama3.2:3b\"}]}",
                "{\"response\":\"{\\\"candidates\\\":[]}\"}")) {
            OllamaSemanticHighlightAnalyzer analyzer = new OllamaSemanticHighlightAnalyzer(
                    server.uri(),
                    "llama3.2:3b",
                    Duration.ofSeconds(2));

            assertTrue(analyzer.isAvailable());
        }
    }

    @Test
    void parsesStructuredCandidateResponse() throws Exception {
        try (TestOllama server = TestOllama.start(
                "{\"models\":[{\"name\":\"llama3.2:3b\"}]}",
                "{\"response\":\"{\\\"candidates\\\":[{\\\"startMs\\\":0,\\\"endMs\\\":5000,\\\"score\\\":0.91,\\\"reason\\\":\\\"strong hook\\\"}]}\"}")) {
            OllamaSemanticHighlightAnalyzer analyzer = new OllamaSemanticHighlightAnalyzer(
                    server.uri(),
                    "llama3.2:3b",
                    Duration.ofSeconds(2));

            HighlightAnalysisResult result = analyzer.analyze(authorization());

            assertEquals(1, result.candidates().size());
            HighlightCandidateResult candidate = result.candidates().getFirst();
            assertEquals(0, candidate.startMs());
            assertEquals(5_000, candidate.endMs());
            assertEquals(0, candidate.score().compareTo(new BigDecimal("0.91")));
            assertEquals("strong hook", candidate.reason());
        }
    }

    @Test
    void malformedProviderJsonIsTerminalFailure() throws Exception {
        try (TestOllama server = TestOllama.start(
                "{\"models\":[{\"name\":\"llama3.2:3b\"}]}",
                "{\"response\":\"not-json\"}")) {
            OllamaSemanticHighlightAnalyzer analyzer = new OllamaSemanticHighlightAnalyzer(
                    server.uri(),
                    "llama3.2:3b",
                    Duration.ofSeconds(2));

            ImportFailureException failure = assertThrows(ImportFailureException.class, () -> analyzer.analyze(authorization()));

            assertEquals("SEMANTIC_RESPONSE_INVALID", failure.code());
            assertTrue(failure.terminal());
        }
    }

    private HighlightAnalysisAuthorization authorization() {
        return new HighlightAnalysisAuthorization(
                UUID.randomUUID(),
                UUID.randomUUID(),
                20_000,
                5,
                3_000,
                60_000,
                "TRANSCRIPT_SEMANTIC_V1",
                "1",
                UUID.randomUUID(),
                List.of(
                        new HighlightTranscriptSegment(0, 0, 5_000, "Bun venit pe platforma multimedia."),
                        new HighlightTranscriptSegment(1, 5_000, 10_000, "Acesta este un test semantic pentru clipuri.")));
    }

    private record TestOllama(HttpServer server, URI uri) implements AutoCloseable {

        static TestOllama start(String tagsResponse, String generateResponse) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/tags", exchange -> {
                byte[] body = tagsResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/api/generate", exchange -> {
                byte[] body = generateResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            return new TestOllama(server, uri);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
