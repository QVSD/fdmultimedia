package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerAgentConfigTest {

    @Test
    void readsConfigurationFromEnvironmentMap() {
        WorkerAgentConfig config = WorkerAgentConfig.from(Map.of(
                "FDM_API_BASE_URL", "http://localhost:8080/api",
                "FDM_WORKER_TOKEN", "credential.secret",
                "FDM_WORKER_NAME", "Local Node",
                "FDM_WORKER_ID_FILE", "worker-id.txt",
                "FDM_WORKER_HEARTBEAT_SECONDS", "5"));

        assertEquals("http://localhost:8080/api/", config.apiBaseUrl().toString());
        assertEquals("WorkerToken credential.secret", config.workerToken());
        assertEquals("Local Node", config.workerName());
        assertEquals(Path.of("worker-id.txt"), config.identityFile());
        assertEquals(Duration.ofSeconds(5), config.heartbeatInterval());
    }

    @Test
    void requiresWorkerToken() {
        assertThrows(IllegalArgumentException.class, () -> WorkerAgentConfig.from(Map.of()));
    }

    @Test
    void rejectsZeroHeartbeatInterval() {
        assertThrows(IllegalArgumentException.class, () -> WorkerAgentConfig.from(Map.of(
                "FDM_WORKER_TOKEN", "credential.secret",
                "FDM_WORKER_HEARTBEAT_SECONDS", "0")));
    }
}
