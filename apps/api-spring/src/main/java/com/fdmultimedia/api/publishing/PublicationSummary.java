package com.fdmultimedia.api.publishing;

import com.fdmultimedia.api.accounts.SocialPlatform;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PublicationSummary(
        UUID id,
        UUID assetId,
        String assetFilename,
        UUID socialAccountId,
        String socialAccountDisplayName,
        SocialPlatform platform,
        PublicationStatus status,
        UUID jobId,
        String caption,
        String providerRequestId,
        String providerPublicationId,
        UUID contentDraftId,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        String failureCode,
        String failureMessage,
        List<PublishingAttemptSummary> attempts) {
}
