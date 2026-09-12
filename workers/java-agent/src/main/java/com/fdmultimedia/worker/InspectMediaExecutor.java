package com.fdmultimedia.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    void download(InspectionAuthorization authorization, Path target)
            throws IOException, InterruptedException, ImportFailureException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(authorization.downloadUrl()))
                .timeout(Duration.ofSeconds(authorization.readTimeoutSeconds()))
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new ImportFailureException("ASSET_DOWNLOAD_FAILED", "Asset download returned HTTP " + response.statusCode(), false);
        }
        long maxBytes = authorization.maxDownloadSizeBytes();
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (contentLength > maxBytes) {
            response.body().close();
            throw new ImportFailureException("ASSET_DOWNLOAD_TOO_LARGE", "Asset download exceeds maximum size", true);
        }
        long size = streamBounded(response.body(), target, maxBytes);
        if (size <= 0) {
            throw new ImportFailureException("ASSET_DOWNLOAD_EMPTY", "Asset download was empty", true);
        }
    }

    private long streamBounded(InputStream input, Path target, long maxBytes)
            throws IOException, ImportFailureException {
        try (input; OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (total + read > maxBytes) {
                    int allowed = (int) Math.max(0, maxBytes - total);
                    if (allowed > 0) {
                        output.write(buffer, 0, allowed);
                    }
                    throw new ImportFailureException("ASSET_DOWNLOAD_TOO_LARGE", "Asset download exceeds maximum size", true);
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return total;
        }
    }
}
