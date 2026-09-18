package com.fdmultimedia.api.jobs;

import java.time.Instant;

public interface QueueSnapshotView {
    long getQueued();
    long getAssigned();
    long getRunning();
    long getSucceeded();
    long getFailed();
    Instant getOldestQueuedAt();
}
