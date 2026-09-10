package com.fdmultimedia.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class SystemTestExecutor {

    private static final long MAX_DURATION_MS = 10_000;
    private static final int MAX_MESSAGE_LENGTH = 200;

    Map<String, Object> execute(ClaimedJob job, String workerName) throws InterruptedException {
        if (!"SYSTEM_TEST".equals(job.type())) {
            throw new IllegalArgumentException("Unsupported job type: " + job.type());
        }
        String message = message(job.payload());
        long durationMs = durationMs(job.payload());
        Instant started = Instant.now();
        if (durationMs > 0) {
            Thread.sleep(durationMs);
        }
        long actualDurationMs = Duration.between(started, Instant.now()).toMillis();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message);
        result.put("workerName", workerName);
        result.put("executionDurationMs", actualDurationMs);
        return result;
    }

    private String message(Map<String, Object> payload) {
        Object value = payload.get("message");
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("SYSTEM_TEST message must be a non-empty string");
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("SYSTEM_TEST message is too long");
        }
        return trimmed;
    }

    private long durationMs(Map<String, Object> payload) {
        Object value = payload.get("durationMs");
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("SYSTEM_TEST durationMs must be numeric");
        }
        long durationMs = number.longValue();
        if (durationMs < 0 || durationMs > MAX_DURATION_MS) {
            throw new IllegalArgumentException("SYSTEM_TEST durationMs is outside the allowed range");
        }
        return durationMs;
    }
}
