package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SystemTestExecutorTest {

    private final SystemTestExecutor executor = new SystemTestExecutor();

    @Test
    void executesSystemTestWithoutCommandExecution() throws Exception {
        ClaimedJob job = new ClaimedJob(
                true,
                UUID.randomUUID(),
                "SYSTEM_TEST",
                Map.of("message", "hello worker", "durationMs", 0),
                1,
                30);

        Map<String, Object> result = executor.execute(job, "local-node");

        assertEquals("hello worker", result.get("message"));
        assertEquals("local-node", result.get("workerName"));
        assertTrue((Long) result.get("executionDurationMs") >= 0);
    }

    @Test
    void rejectsUnsupportedJobType() {
        ClaimedJob job = new ClaimedJob(true, UUID.randomUUID(), "SHELL", Map.of(), 1, 30);

        assertThrows(IllegalArgumentException.class, () -> executor.execute(job, "local-node"));
    }

    @Test
    void validatesPayloadShape() {
        ClaimedJob executablePayload = new ClaimedJob(
                true,
                UUID.randomUUID(),
                "SYSTEM_TEST",
                Map.of("message", "dir", "durationMs", 20_000),
                1,
                30);

        assertThrows(IllegalArgumentException.class, () -> executor.execute(executablePayload, "local-node"));
    }
}
