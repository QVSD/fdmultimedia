package com.fdmultimedia.api.workers;

import java.time.Instant;
import java.util.UUID;

public record WorkerHeartbeatResponse(
        UUID workerId,
        WorkerStatus status,
        Instant serverTime,
        long heartbeatIntervalSeconds,
        long offlineThresholdSeconds) {
}
