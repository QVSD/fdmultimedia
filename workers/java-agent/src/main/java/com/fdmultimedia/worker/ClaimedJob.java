package com.fdmultimedia.worker;

import java.util.Map;
import java.util.UUID;

record ClaimedJob(
        boolean available,
        UUID jobId,
        String type,
        Map<String, Object> payload,
        int attemptCount,
        long leaseSeconds) {
}
