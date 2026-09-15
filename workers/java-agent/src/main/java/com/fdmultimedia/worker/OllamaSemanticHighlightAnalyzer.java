package com.fdmultimedia.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OllamaSemanticHighlightAnalyzer implements HighlightAnalyzer {

    private static final int MAX_RESPONSE_BYTES = 200_000;
    private static final int MAX_PROMPT_CHARS = 120_000;

    private final URI endpoint;
    private final String model;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    OllamaSemanticHighlightAnalyzer(URI endpoint, String model, Duration timeout) {
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
    public HighlightAnalysisResult analyze(HighlightAnalysisAuthorization authorization) throws Exception {
        if (authorization.transcriptSegments() == null || authorization.transcriptSegments().isEmpty()) {
            throw new ImportFailureException("TRANSCRIPT_REQUIRED", "Transcript segments are required", true);
        }
        List<CandidateWindow> windows = candidateWindows(authorization);
        if (windows.isEmpty()) {
            throw new ImportFailureException("SEMANTIC_RESPONSE_INVALID", "Transcript did not contain usable highlight windows", true);
        }
        String prompt = prompt(authorization, windows);
        if (prompt.length() > MAX_PROMPT_CHARS) {
            throw new ImportFailureException("TRANSCRIPT_TOO_LARGE", "Transcript is too large for semantic analysis", true);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);
        body.put("format", "json");
        body.put("options", Map.of("temperature", 0.2, "num_predict", 1200));

        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("api/generate"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new ImportFailureException("SEMANTIC_PROVIDER_UNAVAILABLE", "Semantic provider is temporarily unavailable", false);
        }
        if (response.statusCode() == 429 || response.statusCode() >= 500) {
            throw new ImportFailureException("SEMANTIC_PROVIDER_UNAVAILABLE", "Semantic provider is temporarily unavailable", false);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ImportFailureException("SEMANTIC_PROVIDER_REJECTED", "Semantic provider rejected the request", true);
        }
        if (response.body().length() > MAX_RESPONSE_BYTES) {
            throw new ImportFailureException("SEMANTIC_RESPONSE_TOO_LARGE", "Semantic provider response was too large", true);
        }
        String generated = objectMapper.readTree(response.body()).path("response").asText();
        if (generated.isBlank() || generated.length() > MAX_RESPONSE_BYTES) {
            throw new ImportFailureException("SEMANTIC_RESPONSE_INVALID", "Semantic provider returned invalid output", true);
        }
        return parseGenerated(generated, authorization, windows);
    }

    private String prompt(HighlightAnalysisAuthorization authorization, List<CandidateWindow> windows) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are selecting short social-media highlight candidates from transcript windows only.\n");
        builder.append("Return strict JSON only with this shape: {\"candidates\":[{\"windowId\":\"W1\",\"score\":0.9,\"reason\":\"...\"}]}.\n");
        builder.append("Choose only windowId values listed below. Do not invent timestamps, commands, URLs, files, or media operations.\n");
        builder.append("Candidate count max: ").append(authorization.maxCandidates()).append(". ");
        builder.append("Each candidate duration must be between ").append(authorization.minCandidateDurationMs()).append(" and ");
        builder.append(authorization.maxCandidateDurationMs()).append(" milliseconds.\n");
        builder.append("Asset durationMs: ").append(authorization.durationMs()).append(".\n");
        builder.append("Windows:\n");
        for (CandidateWindow window : windows) {
            builder.append(window.id())
                    .append(" [")
                    .append(window.startMs())
                    .append('-')
                    .append(window.endMs())
                    .append("] ")
                    .append(window.text())
                    .append('\n');
        }
        return builder.toString();
    }

    private HighlightAnalysisResult parseGenerated(String generated, HighlightAnalysisAuthorization authorization, List<CandidateWindow> windows) throws ImportFailureException {
        try {
            JsonNode root = objectMapper.readTree(generated);
            JsonNode candidatesNode = root.path("candidates");
            if (!candidatesNode.isArray()) {
                throw new ImportFailureException("SEMANTIC_RESPONSE_INVALID", "Semantic provider returned no candidates", true);
            }
            List<HighlightCandidateResult> candidates = new ArrayList<>();
            for (JsonNode item : candidatesNode) {
                long startMs = item.path("startMs").asLong(Long.MIN_VALUE);
                long endMs = item.path("endMs").asLong(Long.MIN_VALUE);
                String windowId = item.path("windowId").asText("");
                CandidateWindow window = windowById(windowId, windows);
                if (window != null) {
                    startMs = window.startMs();
                    endMs = window.endMs();
                }
                BigDecimal score = item.hasNonNull("score") ? item.path("score").decimalValue() : null;
                String reason = safeReason(item.path("reason").asText(""), window);
                long[] normalized = normalizeTiming(startMs, endMs, authorization);
                candidates.add(new HighlightCandidateResult(normalized[0], normalized[1], score, reason));
            }
            return new HighlightAnalysisResult(List.copyOf(candidates));
        } catch (ImportFailureException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ImportFailureException("SEMANTIC_RESPONSE_INVALID", "Semantic provider returned malformed JSON", true);
        }
    }

    private long[] normalizeTiming(long startMs, long endMs, HighlightAnalysisAuthorization authorization) throws ImportFailureException {
        if (startMs < 0 || endMs <= startMs) {
            throw new ImportFailureException("SEMANTIC_RESPONSE_INVALID", "Semantic provider returned invalid timestamps", true);
        }
        if (endMs <= Math.max(authorization.durationMs() / 100, 120)) {
            startMs *= 1000;
            endMs *= 1000;
        }
        long[] snapped = snapToSegments(startMs, endMs, authorization.transcriptSegments());
        startMs = snapped[0];
        endMs = snapped[1];
        if (!overlaps(startMs, endMs, authorization.transcriptSegments())) {
            throw new ImportFailureException("SEMANTIC_RESPONSE_INVALID", "Semantic provider returned ungrounded timestamps", true);
        }
        while (endMs - startMs < authorization.minCandidateDurationMs()) {
            HighlightTranscriptSegment next = nextSegment(endMs, authorization.transcriptSegments());
            if (next == null) {
                break;
            }
            endMs = next.endMs();
        }
        if (endMs - startMs > authorization.maxCandidateDurationMs()) {
            endMs = startMs + authorization.maxCandidateDurationMs();
        }
        if (endMs > authorization.durationMs()) {
            endMs = authorization.durationMs();
        }
        if (endMs <= startMs || endMs - startMs < authorization.minCandidateDurationMs()) {
            throw new ImportFailureException("SEMANTIC_RESPONSE_INVALID", "Semantic provider returned unusable candidate duration", true);
        }
        return new long[] {startMs, endMs};
    }

    private long[] snapToSegments(long startMs, long endMs, List<HighlightTranscriptSegment> segments) {
        long snappedStart = startMs;
        long snappedEnd = endMs;
        long bestStartDistance = 2001;
        long bestEndDistance = 2001;
        for (HighlightTranscriptSegment segment : segments) {
            long startDistance = Math.abs(startMs - segment.startMs());
            if (startDistance < bestStartDistance) {
                snappedStart = segment.startMs();
                bestStartDistance = startDistance;
            }
            long endDistance = Math.abs(endMs - segment.endMs());
            if (endDistance < bestEndDistance) {
                snappedEnd = segment.endMs();
                bestEndDistance = endDistance;
            }
        }
        return new long[] {
                bestStartDistance <= 2000 ? snappedStart : startMs,
                bestEndDistance <= 2000 ? snappedEnd : endMs
        };
    }

    private boolean overlaps(long startMs, long endMs, List<HighlightTranscriptSegment> segments) {
        return segments.stream().anyMatch(segment -> startMs < segment.endMs() && endMs > segment.startMs());
    }

    private HighlightTranscriptSegment nextSegment(long endMs, List<HighlightTranscriptSegment> segments) {
        return segments.stream()
                .filter(segment -> segment.endMs() > endMs)
                .findFirst()
                .orElse(null);
    }

    private List<CandidateWindow> candidateWindows(HighlightAnalysisAuthorization authorization) {
        List<CandidateWindow> windows = new ArrayList<>();
        List<HighlightTranscriptSegment> segments = authorization.transcriptSegments();
        for (int index = 0; index < segments.size() && windows.size() < authorization.maxCandidates() * 3; index++) {
            HighlightTranscriptSegment first = segments.get(index);
            long startMs = first.startMs();
            long endMs = first.endMs();
            StringBuilder text = new StringBuilder(normalizeText(first.text()));
            int cursor = index + 1;
            while (endMs - startMs < authorization.minCandidateDurationMs() && cursor < segments.size()) {
                HighlightTranscriptSegment next = segments.get(cursor++);
                endMs = next.endMs();
                text.append(' ').append(normalizeText(next.text()));
            }
            if (endMs > authorization.durationMs()) {
                endMs = authorization.durationMs();
            }
            long duration = endMs - startMs;
            if (duration >= authorization.minCandidateDurationMs() && duration <= authorization.maxCandidateDurationMs()) {
                windows.add(new CandidateWindow("W" + (windows.size() + 1), startMs, endMs, text.toString()));
            }
        }
        return List.copyOf(windows);
    }

    private CandidateWindow windowById(String id, List<CandidateWindow> windows) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return windows.stream()
                .filter(window -> window.id().equalsIgnoreCase(id.trim()))
                .findFirst()
                .orElse(null);
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String safeReason(String reason, CandidateWindow window) {
        String normalized = normalizeText(reason);
        if (!normalized.isBlank() && !"...".equals(normalized)) {
            return normalized;
        }
        if (window == null || window.text().isBlank()) {
            return "Semantic transcript window selected by local provider";
        }
        String excerpt = window.text().length() > 140 ? window.text().substring(0, 140) : window.text();
        return "Transcript segment: " + excerpt;
    }

    private record CandidateWindow(String id, long startMs, long endMs, String text) {
    }
}
