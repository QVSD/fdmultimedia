package com.fdmultimedia.api.contentsources;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AddContentSourceAssetRequest(@NotNull UUID mediaAssetId) {
}
