package com.fdmultimedia.api.contentdrafts;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateContentDraftRequest(
        @NotNull UUID assetId,
        String title,
        String caption) {
}
