package com.fdmultimedia.api.publishschedules;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import com.fdmultimedia.api.publishing.tiktok.TikTokSettingsRequest;

public record CreatePublishScheduleRequest(
        @NotNull UUID socialAccountId,
        @NotNull Instant scheduledFor, TikTokSettingsRequest tiktokSettings) {
    public CreatePublishScheduleRequest(UUID socialAccountId, Instant scheduledFor) { this(socialAccountId, scheduledFor, null); }
}
