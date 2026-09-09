package com.fdmultimedia.api.workers;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class WorkerStatusService {

    private final WorkerProperties properties;
    private final Clock clock;

    public WorkerStatusService(WorkerProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public WorkerStatus statusFor(Instant lastSeenAt) {
        Instant offlineBefore = Instant.now(clock).minus(properties.getOfflineThreshold());
        return lastSeenAt.isBefore(offlineBefore) ? WorkerStatus.OFFLINE : WorkerStatus.ONLINE;
    }
}
