package com.fdmultimedia.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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

    ClaimedJob claim(String machineIdentifier, List<String> supportedJobTypes) throws IOException, InterruptedException {
        return send(
                "/worker-agent/jobs/claim",
                Map.of(
                        "machineIdentifier", machineIdentifier,
                        "supportedJobTypes", supportedJobTypes),
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
        fail(jobId, machineIdentifier, errorCode, errorMessage, false);
    }

    void fail(UUID jobId, String machineIdentifier, String errorCode, String errorMessage, boolean terminal)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("errorCode", errorCode);
        body.put("errorMessage", errorMessage);
        body.put("terminal", terminal);
        send(jobPath(jobId, "fail"), body, new TypeReference<Map<String, Object>>() {});
    }

    void renew(UUID jobId, String machineIdentifier) throws IOException, InterruptedException {
        send(jobPath(jobId, "renew"), Map.of("machineIdentifier", machineIdentifier), new TypeReference<Map<String, Object>>() {});
    }

    ImportMediaAuthorization authorizeImport(UUID jobId, String machineIdentifier)
            throws IOException, InterruptedException {
        return send(
                "/worker-agent/assets/imports/" + jobId + "/authorization",
                Map.of("machineIdentifier", machineIdentifier),
                new TypeReference<ImportMediaAuthorization>() {});
    }

    void completeImport(UUID jobId, String machineIdentifier, ImportMediaAuthorization authorization, DownloadedMedia media)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("assetId", authorization.assetId());
        body.put("storageBucket", authorization.storageBucket());
        body.put("storageKey", authorization.storageKey());
        body.put("originalFilename", media.originalFilename());
        body.put("contentType", media.contentType());
        body.put("fileSizeBytes", media.fileSizeBytes());
        body.put("checksumSha256", media.checksumSha256());
        body.put("containerFormat", media.containerFormat());
        send("/worker-agent/assets/imports/" + jobId + "/complete", body, new TypeReference<Map<String, Object>>() {});
    }

    void failImport(UUID jobId, String machineIdentifier, UUID assetId, String errorCode, String errorMessage, boolean terminal)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("assetId", assetId);
        body.put("errorCode", errorCode);
        body.put("errorMessage", errorMessage);
        body.put("terminal", terminal);
        send("/worker-agent/assets/imports/" + jobId + "/fail", body, new TypeReference<Map<String, Object>>() {});
    }

    InspectionAuthorization authorizeInspection(UUID jobId, String machineIdentifier)
            throws IOException, InterruptedException {
        return send(
                "/worker-agent/assets/inspections/" + jobId + "/authorization",
                Map.of("machineIdentifier", machineIdentifier),
                new TypeReference<InspectionAuthorization>() {});
    }

    void completeInspection(UUID jobId, String machineIdentifier, UUID assetId, InspectionMetadata metadata)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("assetId", assetId);
        body.put("durationMs", metadata.durationMs());
        body.put("width", metadata.width());
        body.put("height", metadata.height());
        body.put("videoCodec", metadata.videoCodec());
        body.put("audioCodec", metadata.audioCodec());
        body.put("containerFormat", metadata.containerFormat());
        body.put("frameRate", metadata.frameRate());
        body.put("bitrate", metadata.bitrate());
        body.put("hasVideo", metadata.hasVideo());
        body.put("hasAudio", metadata.hasAudio());
        send("/worker-agent/assets/inspections/" + jobId + "/complete", body, new TypeReference<Map<String, Object>>() {});
    }

    void failInspection(UUID jobId, String machineIdentifier, UUID assetId, String errorCode, String errorMessage, boolean terminal)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("assetId", assetId);
        body.put("errorCode", errorCode);
        body.put("errorMessage", errorMessage);
        body.put("terminal", terminal);
        send("/worker-agent/assets/inspections/" + jobId + "/fail", body, new TypeReference<Map<String, Object>>() {});
    }

    ClipAuthorization authorizeClip(UUID jobId, String machineIdentifier)
            throws IOException, InterruptedException {
        return send(
                "/worker-agent/assets/clips/" + jobId + "/authorization",
                Map.of("machineIdentifier", machineIdentifier),
                new TypeReference<ClipAuthorization>() {});
    }

    void completeClip(UUID jobId, String machineIdentifier, ClipAuthorization authorization, CreatedClip clip)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("sourceAssetId", authorization.sourceAssetId());
        body.put("outputAssetId", authorization.outputAssetId());
        body.put("checksumSha256", clip.checksumSha256());
        body.put("fileSizeBytes", clip.fileSizeBytes());
        body.put("contentType", clip.contentType());
        body.put("containerFormat", clip.containerFormat());
        send("/worker-agent/assets/clips/" + jobId + "/complete", body, new TypeReference<Map<String, Object>>() {});
    }

    void failClip(UUID jobId, String machineIdentifier, UUID sourceAssetId, UUID outputAssetId, String errorCode, String errorMessage, boolean terminal)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("sourceAssetId", sourceAssetId);
        body.put("outputAssetId", outputAssetId);
        body.put("errorCode", errorCode);
        body.put("errorMessage", errorMessage);
        body.put("terminal", terminal);
        send("/worker-agent/assets/clips/" + jobId + "/fail", body, new TypeReference<Map<String, Object>>() {});
    }

    void upload(URI uploadUrl, Path path, String contentType) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uploadUrl)
                .timeout(Duration.ofMinutes(30))
                .PUT(HttpRequest.BodyPublishers.ofFile(path))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Object upload returned HTTP " + response.statusCode());
        }
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
