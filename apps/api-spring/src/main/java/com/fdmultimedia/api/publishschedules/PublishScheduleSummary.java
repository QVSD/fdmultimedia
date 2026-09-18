package com.fdmultimedia.api.publishschedules;

import com.fdmultimedia.api.accounts.SocialPlatform;
import java.time.Instant;
import java.util.UUID;

public record PublishScheduleSummary(
        UUID id,
        UUID contentDraftId,
        String draftTitle,
        UUID mediaAssetId,
        String mediaAssetFilename,
        UUID socialAccountId,
        String socialAccountDisplayName,
        SocialPlatform platform,
        String captionSnapshot,
        Instant scheduledFor,
        PublishScheduleStatus status,
        UUID publicationId,
        Instant createdAt,
        Instant updatedAt,
        Instant dispatchedAt,
        Instant cancelledAt,
        String failureCode,
        String failureMessage,
        Long dispatchDelayMs) {
}
