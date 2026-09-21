package com.fdmultimedia.api.publishing;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import com.fdmultimedia.api.publishing.tiktok.TikTokSettingsRequest;

public record CreatePublicationRequest(
        @NotNull UUID socialAccountId,
        String caption,
        TikTokSettingsRequest tiktokSettings) {
    public CreatePublicationRequest(UUID socialAccountId, String caption) { this(socialAccountId, caption, null); }
}
