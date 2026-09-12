package com.fdmultimedia.api.assets;

import jakarta.validation.constraints.NotNull;

public record CreateClipRequest(
        @NotNull Long startMs,
        @NotNull Long durationMs) {
}
