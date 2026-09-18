package com.fdmultimedia.api.contentsources;

import jakarta.validation.constraints.NotNull;

public record UpdateContentSourceRequest(@NotNull String name, String description) {
}
