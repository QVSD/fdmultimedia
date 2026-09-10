package com.fdmultimedia.api.jobs;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record JobSummary(
        UUID id,
        JobType type,
        JobStatus status,
        Map<String, Object> payload,
        Map<String, Object> result,
        String errorCode,
        String errorMessage,
        UUID assignedWorkerId,
        String assignedWorkerName,
        int attemptCount,
        int maxAttempts,
        Instant queuedAt,
        Instant assignedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant leaseExpiresAt,
        Instant createdAt,
        Instant updatedAt) {
}
