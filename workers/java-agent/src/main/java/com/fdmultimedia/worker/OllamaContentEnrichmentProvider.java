package com.fdmultimedia.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real local-LLM path via a local Ollama HTTP endpoint — the exact same
 * runtime {@link OllamaSemanticHighlightAnalyzer} already proves works in
 * this repository, reused here structurally (bounded prompt/response sizes,
 * fixed timeout, JSON-format generation) but for the content-enrichment
 * request/response shape instead of highlight candidates. No API key: like
 * the existing semantic-highlight provider, a local Ollama endpoint needs
 * none.
 */
final class OllamaContentEnrichmentProvider implements ContentEnrichmentProvider {

    private static final int MAX_RESPONSE_BYTES = 100_000;

    private final URI endpoint;
    private final String model;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    OllamaContentEnrichmentProvider(URI endpoint, String model, Duration timeout) {
        this.endpoint = endpoint;
        this.model = model;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return false;
            }
            JsonNode models = objectMapper.readTree(response.body()).path("models");
            if (!models.isArray()) {
                return false;
            }
            for (JsonNode item : models) {
                if (model.equals(item.path("name").asText())) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public SocialCopyResult generate(SocialCopyAuthorization authorization) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", authorization.prompt());
        body.put("stream", false);
        body.put("format", "json");
        body.put("options", Map.of("temperature", 0.4, "num_predict", 500));

        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("api/generate"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        long startNanos = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new ImportFailureException("AI_PROVIDER_UNAVAILABLE", "Content AI provider is temporarily unavailable", false);
        }
        long latencyMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ImportFailureException("AI_AUTHENTICATION_FAILED", "Content AI provider rejected authentication", true);
        }
        if (response.statusCode() == 429) {
            throw new ImportFailureException("AI_RATE_LIMITED", "Content AI provider rate limit reached", false);
        }
        if (response.statusCode() >= 500) {
            throw new ImportFailureException("AI_PROVIDER_UNAVAILABLE", "Content AI provider is temporarily unavailable", false);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ImportFailureException("AI_PROVIDER_UNAVAILABLE", "Content AI provider rejected the request", true);
        }
        if (response.body().length() > MAX_RESPONSE_BYTES) {
            throw new ImportFailureException("AI_INVALID_RESPONSE", "Content AI provider response was too large", true);
        }
        String generated = objectMapper.readTree(response.body()).path("response").asText();
        if (generated.isBlank() || generated.length() > MAX_RESPONSE_BYTES) {
            throw new ImportFailureException("AI_INVALID_RESPONSE", "Content AI provider returned invalid output", true);
        }
        return parse(generated, latencyMs);
    }

    private SocialCopyResult parse(String generated, long latencyMs) throws ImportFailureException {
        try {
            JsonNode root = objectMapper.readTree(generated);
            String hook = root.path("hook").asText(null);
            String caption = root.path("caption").asText(null);
            String shortTitle = root.hasNonNull("shortTitle") ? root.path("shortTitle").asText(null) : null;
            List<String> hashtags = new ArrayList<>();
            JsonNode hashtagsNode = root.path("hashtags");
            if (hashtagsNode.isArray()) {
                for (JsonNode tag : hashtagsNode) {
                    if (tag.isTextual()) {
                        hashtags.add(tag.asText());
                    }
                }
            }
            return new SocialCopyResult(hook, caption, List.copyOf(hashtags), shortTitle, null, null, null, latencyMs);
        } catch (Exception ex) {
            throw new ImportFailureException("AI_INVALID_RESPONSE", "Content AI provider returned malformed JSON", true);
        }
    }
}
