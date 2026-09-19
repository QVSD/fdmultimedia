package com.fdmultimedia.api.contentsuggestions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Raw, still-untrusted provider output as parsed by the Worker. The backend
 * performs the authoritative validation/normalization in
 * {@code ContentSuggestionService.completeWorkerGeneration} — this request
 * shape only carries what the Worker observed, nothing is trusted merely
 * because it arrived over a WorkerToken-authenticated call.
 */
public record WorkerContentSuggestionCompletionRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID suggestionId,
        String hook,
        String caption,
        List<String> hashtags,
        String shortTitle,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long latencyMs) {
}
