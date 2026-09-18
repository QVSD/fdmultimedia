package com.fdmultimedia.api.contentdrafts;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PublishContentDraftRequest(@NotNull UUID socialAccountId) {
}
