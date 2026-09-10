package com.fdmultimedia.api.assets;

import jakarta.validation.constraints.NotBlank;

public record MediaImportRequest(@NotBlank String url) {
}
