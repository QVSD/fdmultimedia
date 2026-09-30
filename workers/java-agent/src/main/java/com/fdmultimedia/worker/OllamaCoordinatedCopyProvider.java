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
import java.util.UUID;

/**
 * Real local-LLM path via a local Ollama HTTP endpoint — structurally
 * identical to {@link OllamaCampaignPlanProvider} (bounded prompt/response
 * sizes, fixed timeout, JSON-format generation), reused here for the
 * coordinated-copy request/response shape instead. No API key needed, same
 * as the other local Ollama-backed providers in this repository.
 */
final class OllamaCoordinatedCopyProvider implements CoordinatedCopyProvider {

    private static final int MAX_RESPONSE_BYTES = 100_000;

    private final URI endpoint;
    private final String model;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    OllamaCoordinatedCopyProvider(URI endpoint, String model, Duration timeout) {
        this.endpoint = endpoint;
        this.model = model;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public CoordinatedCopyResult generate(CoordinatedCopyAuthorization authorization) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", authorization.prompt());
        body.put("stream", false);
        body.put("format", "json");
        body.put("options", Map.of("temperature", 0.4, "num_predict", 1200));

        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("api/generate"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new ImportFailureException("AI_PROVIDER_UNAVAILABLE", "Coordinated copy provider is temporarily unavailable", false);
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ImportFailureException("AI_AUTHENTICATION_FAILED", "Coordinated copy provider rejected authentication", true);
        }
        if (response.statusCode() == 429) {
            throw new ImportFailureException("AI_RATE_LIMITED", "Coordinated copy provider rate limit reached", false);
        }
        if (response.statusCode() >= 500) {
            throw new ImportFailureException("AI_PROVIDER_UNAVAILABLE", "Coordinated copy provider is temporarily unavailable", false);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ImportFailureException("AI_PROVIDER_UNAVAILABLE", "Coordinated copy provider rejected the request", true);
        }
        if (response.body().length() > MAX_RESPONSE_BYTES) {
            throw new ImportFailureException("AI_INVALID_RESPONSE", "Coordinated copy provider response was too large", true);
        }
        String generated = objectMapper.readTree(response.body()).path("response").asText();
        if (generated.isBlank() || generated.length() > MAX_RESPONSE_BYTES) {
            throw new ImportFailureException("AI_INVALID_RESPONSE", "Coordinated copy provider returned invalid output", true);
        }
        return parse(generated);
    }

    private CoordinatedCopyResult parse(String generated) throws ImportFailureException {
        try {
            JsonNode root = objectMapper.readTree(generated);
            String seriesTitle = root.path("seriesTitle").asText(null);
            List<CoordinatedCopyItemResult> items = new ArrayList<>();
            JsonNode itemsNode = root.path("items");
            if (itemsNode.isArray()) {
                for (JsonNode item : itemsNode) {
                    UUID outputId;
                    try {
                        outputId = UUID.fromString(item.path("outputId").asText(""));
                    } catch (IllegalArgumentException ex) {
                        continue; // let the backend's authoritative validation reject a malformed/missing outputId
                    }
                    List<String> hashtags = new ArrayList<>();
                    JsonNode hashtagsNode = item.path("hashtags");
                    if (hashtagsNode.isArray()) {
                        for (JsonNode tag : hashtagsNode) {
                            if (tag.isTextual()) {
                                hashtags.add(tag.asText());
                            }
                        }
                    }
                    items.add(new CoordinatedCopyItemResult(
                            outputId,
                            item.path("hook").asText(null),
                            item.path("caption").asText(null),
                            List.copyOf(hashtags),
                            item.hasNonNull("shortTitle") ? item.path("shortTitle").asText(null) : null,
                            item.hasNonNull("continuityNote") ? item.path("continuityNote").asText(null) : null));
                }
            }
            return new CoordinatedCopyResult(seriesTitle, List.copyOf(items));
        } catch (Exception ex) {
            throw new ImportFailureException("AI_INVALID_RESPONSE", "Coordinated copy provider returned malformed JSON", true);
        }
    }
}
