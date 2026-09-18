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

    void heartbeat(
            String machineIdentifier,
            WorkerTelemetry telemetry,
            List<String> supportedJobTypes,
            List<String> supportedHighlightAnalyzers,
            int maxActiveJobs) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("telemetry", telemetryBody(telemetry));
        body.put("supportedJobTypes", supportedJobTypes);
        body.put("supportedHighlightAnalyzers", supportedHighlightAnalyzers);
        body.put("maxActiveJobs", maxActiveJobs);
        send("/worker-agent/heartbeat", body, new TypeReference<Map<String, Object>>() {});
    }

    void heartbeat(
            String machineIdentifier,
            WorkerTelemetry telemetry,
            List<String> supportedJobTypes,
            List<String> supportedHighlightAnalyzers) throws IOException, InterruptedException {
        heartbeat(machineIdentifier, telemetry, supportedJobTypes, supportedHighlightAnalyzers, 1);
    }

    void heartbeat(String machineIdentifier) throws IOException, InterruptedException {
        send("/worker-agent/heartbeat", Map.of("machineIdentifier", machineIdentifier), new TypeReference<Map<String, Object>>() {});
    }

    ClaimedJob claim(
            String machineIdentifier,
            List<String> supportedJobTypes,
            List<String> supportedHighlightAnalyzers,
            List<String> supportedPublishingProviders) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("supportedJobTypes", supportedJobTypes);
        body.put("supportedHighlightAnalyzers", supportedHighlightAnalyzers);
        body.put("supportedPublishingProviders", supportedPublishingProviders);
        return send("/worker-agent/jobs/claim", body, new TypeReference<ClaimedJob>() {});
    }

    ClaimedJob claim(String machineIdentifier, List<String> supportedJobTypes, List<String> supportedHighlightAnalyzers) throws IOException, InterruptedException {
        return claim(machineIdentifier, supportedJobTypes, supportedHighlightAnalyzers, List.of("TEST"));
    }

    ClaimedJob claim(String machineIdentifier, List<String> supportedJobTypes) throws IOException, InterruptedException {
        return claim(machineIdentifier, supportedJobTypes, List.of("DETERMINISTIC_V1"), List.of("TEST"));
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
        body.put("originalFilename", clip.originalFilename());
        body.put("contentType", clip.contentType());
        body.put("containerFormat", clip.containerFormat());
        send("/worker-agent/assets/clips/" + jobId + "/complete", body, new TypeReference<Map<String, Object>>() {});
    }

    ClipAuthorization authorizeSocialVertical(UUID jobId, String machineIdentifier)
            throws IOException, InterruptedException {
        return send(
                "/worker-agent/assets/social-verticals/" + jobId + "/authorization",
                Map.of("machineIdentifier", machineIdentifier),
                new TypeReference<ClipAuthorization>() {});
    }

    void completeSocialVertical(UUID jobId, String machineIdentifier, ClipAuthorization authorization, CreatedClip clip)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("sourceAssetId", authorization.sourceAssetId());
        body.put("outputAssetId", authorization.outputAssetId());
        body.put("checksumSha256", clip.checksumSha256());
        body.put("fileSizeBytes", clip.fileSizeBytes());
        body.put("originalFilename", clip.originalFilename());
        body.put("contentType", clip.contentType());
        body.put("containerFormat", clip.containerFormat());
        send("/worker-agent/assets/social-verticals/" + jobId + "/complete", body, new TypeReference<Map<String, Object>>() {});
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

    void failSocialVertical(UUID jobId, String machineIdentifier, UUID sourceAssetId, UUID outputAssetId, String errorCode, String errorMessage, boolean terminal)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("sourceAssetId", sourceAssetId);
        body.put("outputAssetId", outputAssetId);
        body.put("errorCode", errorCode);
        body.put("errorMessage", errorMessage);
        body.put("terminal", terminal);
        send("/worker-agent/assets/social-verticals/" + jobId + "/fail", body, new TypeReference<Map<String, Object>>() {});
    }

    HighlightAnalysisAuthorization authorizeHighlightAnalysis(UUID jobId, String machineIdentifier)
            throws IOException, InterruptedException {
        return send(
                "/worker-agent/highlights/" + jobId + "/authorization",
                Map.of("machineIdentifier", machineIdentifier),
                new TypeReference<HighlightAnalysisAuthorization>() {});
    }

    void completeHighlightAnalysis(UUID jobId, String machineIdentifier, HighlightAnalysisAuthorization authorization, HighlightAnalysisResult result)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("analysisId", authorization.analysisId());
        body.put("assetId", authorization.assetId());
        body.put("candidates", result.candidates());
        send("/worker-agent/highlights/" + jobId + "/complete", body, new TypeReference<Map<String, Object>>() {});
    }

    void failHighlightAnalysis(UUID jobId, String machineIdentifier, UUID analysisId, UUID assetId, String errorCode, String errorMessage, boolean terminal)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("analysisId", analysisId);
        body.put("assetId", assetId);
        body.put("errorCode", errorCode);
        body.put("errorMessage", errorMessage);
        body.put("terminal", terminal);
        send("/worker-agent/highlights/" + jobId + "/fail", body, new TypeReference<Map<String, Object>>() {});
    }

    TranscriptionAuthorization authorizeTranscription(UUID jobId, String machineIdentifier)
            throws IOException, InterruptedException {
        return send(
                "/worker-agent/transcripts/" + jobId + "/authorization",
                Map.of("machineIdentifier", machineIdentifier),
                new TypeReference<TranscriptionAuthorization>() {});
    }

    void completeTranscription(UUID jobId, String machineIdentifier, TranscriptionAuthorization authorization, TranscriptionResult result)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("transcriptId", authorization.transcriptId());
        body.put("assetId", authorization.assetId());
        body.put("detectedLanguage", result.detectedLanguage());
        body.put("durationMs", result.durationMs());
        body.put("segments", result.segments());
        send("/worker-agent/transcripts/" + jobId + "/complete", body, new TypeReference<Map<String, Object>>() {});
    }

    void failTranscription(UUID jobId, String machineIdentifier, UUID transcriptId, UUID assetId, String errorCode, String errorMessage, boolean terminal)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("transcriptId", transcriptId);
        body.put("assetId", assetId);
        body.put("errorCode", errorCode);
        body.put("errorMessage", errorMessage);
        body.put("terminal", terminal);
        send("/worker-agent/transcripts/" + jobId + "/fail", body, new TypeReference<Map<String, Object>>() {});
    }

    PublicationAuthorization authorizePublication(UUID jobId, String machineIdentifier)
            throws IOException, InterruptedException {
        return send(
                "/worker-agent/publications/" + jobId + "/authorization",
                Map.of("machineIdentifier", machineIdentifier),
                new TypeReference<PublicationAuthorization>() {});
    }

    void completePublication(UUID jobId, String machineIdentifier, PublicationAuthorization authorization, PublishResult result)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("publicationId", authorization.publicationId());
        body.put("assetId", authorization.assetId());
        body.put("socialAccountId", authorization.socialAccountId());
        body.put("providerRequestId", result.providerRequestId());
        body.put("providerPublicationId", result.providerPublicationId());
        body.put("publishedAt", result.publishedAt() == null ? null : result.publishedAt().toString());
        send("/worker-agent/publications/" + jobId + "/complete", body, new TypeReference<Map<String, Object>>() {});
    }

    void failPublication(
            UUID jobId,
            String machineIdentifier,
            UUID publicationId,
            UUID assetId,
            UUID socialAccountId,
            String errorCode,
            String errorMessage,
            boolean terminal) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineIdentifier", machineIdentifier);
        body.put("publicationId", publicationId);
        body.put("assetId", assetId);
        body.put("socialAccountId", socialAccountId);
        body.put("errorCode", errorCode);
        body.put("errorMessage", errorMessage);
        body.put("terminal", terminal);
        send("/worker-agent/publications/" + jobId + "/fail", body, new TypeReference<Map<String, Object>>() {});
    }

    InstagramDriveStatus driveInstagramPublication(UUID jobId, String machineIdentifier)
            throws IOException, InterruptedException {
        return send(
                "/worker-agent/publications/" + jobId + "/instagram/drive",
                Map.of("machineIdentifier", machineIdentifier),
                new TypeReference<InstagramDriveStatus>() {});
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

    private Map<String, Object> telemetryBody(WorkerTelemetry telemetry) {
        if (telemetry == null) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemCpuLoad", telemetry.systemCpuLoad());
        body.put("processCpuLoad", telemetry.processCpuLoad());
        body.put("availableMemoryBytes", telemetry.availableMemoryBytes());
        body.put("jvmHeapUsedBytes", telemetry.jvmHeapUsedBytes());
        body.put("jvmHeapMaxBytes", telemetry.jvmHeapMaxBytes());
        body.put("activeJobs", telemetry.activeJobs());
        return body;
    }

    private URI trimLeadingSlash(String path) {
        return URI.create(path.startsWith("/") ? path.substring(1) : path);
    }

    private String jobPath(UUID jobId, String action) {
        return "/worker-agent/jobs/" + jobId + "/" + action;
    }
}
