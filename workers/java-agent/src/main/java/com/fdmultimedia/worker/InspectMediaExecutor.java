package com.fdmultimedia.worker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

final class InspectMediaExecutor {

    private final FfprobeMediaInspector inspector;
    private final HttpClient httpClient;

    InspectMediaExecutor(String ffprobePath) {
        this(new FfprobeMediaInspector(ffprobePath));
    }

    InspectMediaExecutor(FfprobeMediaInspector inspector) {
        this.inspector = inspector;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    void execute(WorkerAgentClient client, ClaimedJob job, String machineIdentifier)
            throws IOException, InterruptedException, ImportFailureException {
        InspectionAuthorization authorization = client.authorizeInspection(job.jobId(), machineIdentifier);
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("fdm-inspect-", ".media");
            download(authorization, tempFile);
            InspectionMetadata metadata = inspector.inspect(tempFile);
            client.completeInspection(job.jobId(), machineIdentifier, authorization.assetId(), metadata);
        } finally {
            if (tempFile != null) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private void download(InspectionAuthorization authorization, Path target)
            throws IOException, InterruptedException, ImportFailureException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(authorization.downloadUrl()))
                .timeout(Duration.ofSeconds(authorization.readTimeoutSeconds()))
                .GET()
                .build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ImportFailureException("ASSET_DOWNLOAD_FAILED", "Asset download returned HTTP " + response.statusCode(), false);
        }
        long size = Files.size(target);
        if (size <= 0) {
            throw new ImportFailureException("ASSET_DOWNLOAD_EMPTY", "Asset download was empty", true);
        }
        if (size > authorization.maxDownloadSizeBytes()) {
            throw new ImportFailureException("ASSET_DOWNLOAD_TOO_LARGE", "Asset download exceeds maximum size", true);
        }
    }
}
