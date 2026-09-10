package com.fdmultimedia.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class WorkerAgentClient {

    private final URI apiBaseUrl;
    private final String authorizationHeader;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    WorkerAgentClient(URI apiBaseUrl, String authorizationHeader) {
        this.apiBaseUrl = apiBaseUrl;
        this.authorizationHeader = authorizationHeader;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    void register(MachineInfo machineInfo) throws IOException, InterruptedException {
        send("/worker-agent/register", registrationBody(machineInfo), new TypeReference<Map<String, Object>>() {});
    }

    void heartbeat(String machineIdentifier) throws IOException, InterruptedException {
        send("/worker-agent/heartbeat", Map.of("machineIdentifier", machineIdentifier), new TypeReference<Map<String, Object>>() {});
    }

    ClaimedJob claim(String machineIdentifier) throws IOException, InterruptedException {
        return send(
                "/worker-agent/jobs/claim",
                Map.of("machineIdentifier", machineIdentifier),
                new TypeReference<ClaimedJob>() {});
    }

    void started(UUID jobId, String machineIdentifier) throws IOException, InterruptedException {
        send(jobPath(jobId, "started"), Map.of("machineIdentifier", machineIdentifier), new TypeReference<Map<String, Object>>() {});
    }

    void complete(UUID jobId, String machineIdentifier, Map<String, Object> result)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("result", result);
        send(jobPath(jobId, "complete"), body, new TypeReference<Map<String, Object>>() {});
    }

    void fail(UUID jobId, String machineIdentifier, String errorCode, String errorMessage)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("errorCode", errorCode);
        body.put("errorMessage", errorMessage);
        send(jobPath(jobId, "fail"), body, new TypeReference<Map<String, Object>>() {});
    }

    private <T> T send(String path, Object body, TypeReference<T> responseType) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(apiBaseUrl.resolve(trimLeadingSlash(path)))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authorizationHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Worker API returned HTTP " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), responseType);
    }

    private Map<String, Object> registrationBody(MachineInfo info) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", info.machineIdentifier());
        body.put("name", info.name());
        body.put("operatingSystem", info.operatingSystem());
        body.put("architecture", info.architecture());
        body.put("cpuModel", info.cpuModel());
        body.put("cpuLogicalCores", info.cpuLogicalCores());
        body.put("totalMemoryBytes", info.totalMemoryBytes());
        body.put("gpuModel", info.gpuModel());
        body.put("gpuMemoryBytes", info.gpuMemoryBytes());
        body.put("agentVersion", info.agentVersion());
        return body;
    }

    private URI trimLeadingSlash(String path) {
        return URI.create(path.startsWith("/") ? path.substring(1) : path);
    }

    private String jobPath(UUID jobId, String action) {
        return "/worker-agent/jobs/" + jobId + "/" + action;
    }
}
