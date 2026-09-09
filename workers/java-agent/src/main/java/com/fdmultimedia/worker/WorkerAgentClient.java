package com.fdmultimedia.worker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class WorkerAgentClient {

    private final URI apiBaseUrl;
    private final String authorizationHeader;
    private final HttpClient httpClient;

    WorkerAgentClient(URI apiBaseUrl, String authorizationHeader) {
        this.apiBaseUrl = apiBaseUrl;
        this.authorizationHeader = authorizationHeader;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    void register(MachineInfo machineInfo) throws IOException, InterruptedException {
        send("/worker-agent/register", registrationJson(machineInfo));
    }

    void heartbeat(String machineIdentifier) throws IOException, InterruptedException {
        send("/worker-agent/heartbeat", "{\"machineIdentifier\":\"" + escape(machineIdentifier) + "\"}");
    }

    private void send(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(apiBaseUrl.resolve(trimLeadingSlash(path)))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authorizationHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Worker API returned HTTP " + response.statusCode());
        }
    }

    private String registrationJson(MachineInfo info) {
        StringBuilder json = new StringBuilder();
        json.append('{')
                .append(field("machineIdentifier", info.machineIdentifier())).append(',')
                .append(field("name", info.name())).append(',')
                .append(field("operatingSystem", info.operatingSystem())).append(',')
                .append(field("architecture", info.architecture())).append(',')
                .append(field("cpuModel", info.cpuModel())).append(',')
                .append("\"cpuLogicalCores\":").append(info.cpuLogicalCores()).append(',')
                .append("\"totalMemoryBytes\":").append(info.totalMemoryBytes()).append(',')
                .append(nullableField("gpuModel", info.gpuModel())).append(',')
                .append("\"gpuMemoryBytes\":").append(info.gpuMemoryBytes() == null ? "null" : info.gpuMemoryBytes()).append(',')
                .append(field("agentVersion", info.agentVersion()))
                .append('}');
        return json.toString();
    }

    private String field(String name, String value) {
        return "\"" + name + "\":\"" + escape(value) + "\"";
    }

    private String nullableField(String name, String value) {
        return "\"" + name + "\":" + (value == null ? "null" : "\"" + escape(value) + "\"");
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private URI trimLeadingSlash(String path) {
        return URI.create(path.startsWith("/") ? path.substring(1) : path);
    }
}
