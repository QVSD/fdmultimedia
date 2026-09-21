package com.fdmultimedia.api.contentdrafts;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import com.fdmultimedia.api.publishing.tiktok.TikTokSettingsRequest;

public record PublishContentDraftRequest(@NotNull UUID socialAccountId, TikTokSettingsRequest tiktokSettings) {
    public PublishContentDraftRequest(UUID socialAccountId) { this(socialAccountId, null); }
}
