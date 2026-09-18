package com.fdmultimedia.api.publishing;

import java.time.Instant;
import java.util.UUID;

public record PublishingAttemptSummary(
        UUID id,
        int jobAttempt,
        UUID workerId,
        String workerName,
        Instant startedAt,
        Instant finishedAt,
        PublishingAttemptOutcome outcome,
        String providerRequestId,
        String providerPublicationId,
        String errorCode,
        String errorMessage) {
}
