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
                "FFPROBE_PATH", "custom-ffprobe",
                "FDM_WORKER_ID_FILE", "worker-id.txt",
                "FDM_WORKER_HEARTBEAT_SECONDS", "5",
                "FDM_WORKER_JOB_POLL_SECONDS", "2",
                "WORKER_MAX_ACTIVE_JOBS", "3"));

        assertEquals("http://localhost:8080/api/", config.apiBaseUrl().toString());
        assertEquals("WorkerToken credential.secret", config.workerToken());
        assertEquals("Local Node", config.workerName());
        assertEquals("custom-ffprobe", config.ffprobePath());
        assertEquals(3, config.maxActiveJobs());
        assertEquals(Path.of("worker-id.txt"), config.identityFile());
        assertEquals(Duration.ofSeconds(5), config.heartbeatInterval());
        assertEquals(Duration.ofSeconds(2), config.jobPollInterval());
        assertEquals(false, config.instagramPublishingEnabled());
    }

    @Test
    void instagramPublishingDisabledByDefault() {
        WorkerAgentConfig config = WorkerAgentConfig.from(Map.of("FDM_WORKER_TOKEN", "credential.secret"));

        assertEquals(false, config.instagramPublishingEnabled());
    }

    @Test
    void instagramPublishingCanBeExplicitlyEnabled() {
        WorkerAgentConfig config = WorkerAgentConfig.from(Map.of(
                "FDM_WORKER_TOKEN", "credential.secret",
                "WORKER_INSTAGRAM_PUBLISHING_ENABLED", "true"));

        assertEquals(true, config.instagramPublishingEnabled());
    }

    @Test
    void contentAiRuntimeIsEmptyByDefault() {
        WorkerAgentConfig config = WorkerAgentConfig.from(Map.of("FDM_WORKER_TOKEN", "credential.secret"));

        assertEquals("", config.contentAiRuntime());
        assertEquals(Duration.ofSeconds(60), config.contentAiTimeout());
    }

    @Test
    void contentAiRuntimeCanBeExplicitlyConfigured() {
        WorkerAgentConfig config = WorkerAgentConfig.from(Map.of(
                "FDM_WORKER_TOKEN", "credential.secret",
                "CONTENT_AI_RUNTIME", "OLLAMA",
                "CONTENT_AI_ENDPOINT", "http://localhost:11434",
                "CONTENT_AI_MODEL", "llama3.2:1b",
                "CONTENT_AI_TIMEOUT_SECONDS", "30"));

        assertEquals("OLLAMA", config.contentAiRuntime());
        assertEquals("http://localhost:11434/", config.contentAiEndpoint().toString());
        assertEquals("llama3.2:1b", config.contentAiModel());
        assertEquals(Duration.ofSeconds(30), config.contentAiTimeout());
    }

    @Test
    void rejectsZeroContentAiTimeout() {
        assertThrows(IllegalArgumentException.class, () -> WorkerAgentConfig.from(Map.of(
                "FDM_WORKER_TOKEN", "credential.secret",
                "CONTENT_AI_TIMEOUT_SECONDS", "0")));
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

    @Test
    void rejectsZeroJobPollInterval() {
        assertThrows(IllegalArgumentException.class, () -> WorkerAgentConfig.from(Map.of(
                "FDM_WORKER_TOKEN", "credential.secret",
                "FDM_WORKER_JOB_POLL_SECONDS", "0")));
    }

    @Test
    void rejectsZeroMaxActiveJobs() {
        assertThrows(IllegalArgumentException.class, () -> WorkerAgentConfig.from(Map.of(
                "FDM_WORKER_TOKEN", "credential.secret",
                "WORKER_MAX_ACTIVE_JOBS", "0")));
    }
}
