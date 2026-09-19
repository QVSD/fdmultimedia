package com.fdmultimedia.api.contentsuggestions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkerContentSuggestionFailureRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID suggestionId,
        String errorCode,
        String errorMessage,
        Boolean terminal) {
}
