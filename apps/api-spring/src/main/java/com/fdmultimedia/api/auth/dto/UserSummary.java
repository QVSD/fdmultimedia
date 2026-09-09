package com.fdmultimedia.api.auth.dto;

import java.util.UUID;

public record UserSummary(
        UUID id,
        String email,
        String displayName) {
}
