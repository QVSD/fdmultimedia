package com.fdmultimedia.worker;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

record WorkerAgentConfig(
        URI apiBaseUrl,
        String workerToken,
        String workerName,
        String ffprobePath,
        String ffmpegPath,
        Path identityFile,
        Duration heartbeatInterval,
        Duration jobPollInterval) {

    static WorkerAgentConfig fromEnvironment() {
        return from(System.getenv());
    }

    static WorkerAgentConfig from(Map<String, String> environment) {
        URI apiBaseUrl = normalizeBaseUrl(environment.getOrDefault("FDM_API_BASE_URL", "http://localhost:8080/api"));
        String token = required(environment, "FDM_WORKER_TOKEN");
        String workerName = environment.getOrDefault("FDM_WORKER_NAME", localHostname());
        String ffprobePath = environment.getOrDefault("FFPROBE_PATH", "ffprobe").trim();
        String ffmpegPath = environment.getOrDefault("FFMPEG_PATH", "ffmpeg").trim();
        Path identityFile = Path.of(environment.getOrDefault(
                "FDM_WORKER_ID_FILE",
                Path.of(System.getProperty("user.home"), ".fdmultimedia", "worker-id").toString()));
        Duration heartbeatInterval = Duration.ofSeconds(
                Long.parseLong(environment.getOrDefault("FDM_WORKER_HEARTBEAT_SECONDS", "10")));
        if (heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("FDM_WORKER_HEARTBEAT_SECONDS must be greater than zero");
        }
        Duration jobPollInterval = Duration.ofSeconds(
                Long.parseLong(environment.getOrDefault("FDM_WORKER_JOB_POLL_SECONDS", "3")));
        if (jobPollInterval.isNegative() || jobPollInterval.isZero()) {
            throw new IllegalArgumentException("FDM_WORKER_JOB_POLL_SECONDS must be greater than zero");
        }
        return new WorkerAgentConfig(
                apiBaseUrl,
                normalizeToken(token),
                workerName,
                ffprobePath.isBlank() ? "ffprobe" : ffprobePath,
                ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath,
                identityFile,
                heartbeatInterval,
                jobPollInterval);
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.trim();
    }

    private static String normalizeToken(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("WorkerToken ") ? trimmed : "WorkerToken " + trimmed;
    }

    private static URI normalizeBaseUrl(String value) {
        String trimmed = value.trim();
        return URI.create(trimmed.endsWith("/") ? trimmed : trimmed + "/");
    }

    private static String localHostname() {
        String computerName = System.getenv("COMPUTERNAME");
        if (computerName != null && !computerName.isBlank()) {
            return computerName;
        }
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        return "fdm-worker";
    }
}
