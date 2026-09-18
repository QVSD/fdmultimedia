package com.fdmultimedia.api.contentsources;

import jakarta.validation.constraints.NotNull;

public record CreateContentSourceRequest(@NotNull String name, String description) {
}
