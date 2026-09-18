package com.fdmultimedia.api.accounts;

import jakarta.validation.constraints.NotBlank;

public record CreateSocialAccountRequest(
        @NotBlank String platform,
        @NotBlank String displayName) {
}
