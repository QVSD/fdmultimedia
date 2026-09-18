package com.fdmultimedia.api.publishing;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreatePublicationRequest(
        @NotNull UUID socialAccountId,
        String caption) {
}
