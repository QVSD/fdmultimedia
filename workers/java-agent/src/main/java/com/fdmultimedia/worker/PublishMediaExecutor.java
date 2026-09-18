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

final class PublishMediaExecutor {

    private final PublishingProvider provider;
    private final HttpClient httpClient;

    PublishMediaExecutor(PublishingProvider provider) {
        this.provider = provider;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    void execute(WorkerAgentClient client, ClaimedJob job, String machineIdentifier)
            throws IOException, InterruptedException, ImportFailureException {
        PublicationAuthorization authorization = client.authorizePublication(job.jobId(), machineIdentifier);
        Path media = null;
        try {
            media = Files.createTempFile("fdm-publish-media-", ".media");
            download(authorization, media);
            PublishResult result = provider.publish(media, authorization);
            client.completePublication(job.jobId(), machineIdentifier, authorization, result);
        } finally {
            if (media != null) {
                Files.deleteIfExists(media);
            }
        }
    }

    private void download(PublicationAuthorization authorization, Path target)
            throws IOException, InterruptedException, ImportFailureException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(authorization.downloadUrl()))
                .timeout(Duration.ofSeconds(authorization.readTimeoutSeconds()))
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new ImportFailureException("PUBLISH_SOURCE_DOWNLOAD_FAILED", "Source download returned HTTP " + response.statusCode(), false);
        }
        long maxBytes = authorization.maxDownloadSizeBytes();
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (contentLength > maxBytes) {
            response.body().close();
            throw new ImportFailureException("PUBLISH_SOURCE_TOO_LARGE", "Source download exceeds maximum size", true);
        }
        long size = streamBounded(response.body(), target, maxBytes);
        if (size <= 0) {
            throw new ImportFailureException("PUBLISH_SOURCE_EMPTY", "Source download was empty", true);
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
                    throw new ImportFailureException("PUBLISH_SOURCE_TOO_LARGE", "Source download exceeds maximum size", true);
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return total;
        }
    }
}
