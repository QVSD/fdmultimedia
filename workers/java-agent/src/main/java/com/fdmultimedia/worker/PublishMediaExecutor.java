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
import java.time.Instant;

final class PublishMediaExecutor {

    // Bounded, and deliberately somewhat longer than the backend's own default
    // containerProcessingTimeout (5 minutes): in the normal case the backend
    // detects its own timeout first and returns a proper terminal
    // INSTAGRAM_PROCESSING_TIMEOUT outcome. This is only a last-resort local
    // safety net against the Worker looping forever if something prevents the
    // backend's own check from ever being reached.
    private static final Duration INSTAGRAM_DRIVE_MAX_DURATION = Duration.ofMinutes(8);
    private static final Duration INSTAGRAM_DRIVE_POLL_INTERVAL = Duration.ofSeconds(5);

    private final PublishingProvider provider;
    private final HttpClient httpClient;

    PublishMediaExecutor(PublishingProvider provider) {
        this.provider = provider;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    void execute(WorkerAgentClient client, ClaimedJob job, String machineIdentifier)
            throws IOException, InterruptedException, ImportFailureException {
        PublicationAuthorization authorization = client.authorizePublication(job.jobId(), machineIdentifier);
        if (!"TEST".equals(authorization.platform())) {
            // Real providers (Instagram) keep every credential-bearing call on
            // the backend; the Worker only drives a bounded poll loop and never
            // downloads media or sees a provider token.
            driveUntilTerminal(client, job, machineIdentifier, authorization.platform());
            return;
        }
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

    private void driveUntilTerminal(WorkerAgentClient client, ClaimedJob job, String machineIdentifier, String platform)
            throws IOException, InterruptedException, ImportFailureException {
        if (!"INSTAGRAM".equals(platform) && !"TIKTOK".equals(platform)) {
            throw new ImportFailureException("PUBLISH_UNSUPPORTED_PLATFORM", "Worker cannot drive platform " + platform, true);
        }
        Instant deadline = Instant.now().plus(INSTAGRAM_DRIVE_MAX_DURATION);
        while (true) {
            InstagramDriveStatus status = "TIKTOK".equals(platform)
                    ? client.driveTikTokPublication(job.jobId(), machineIdentifier)
                    : client.driveInstagramPublication(job.jobId(), machineIdentifier);
            if (!"IN_PROGRESS".equals(status.status())) {
                // PUBLISHED or FAILED: the backend has already finalized the Job
                // and Publication as part of that call. Nothing left to report.
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new ImportFailureException("INSTAGRAM_PROCESSING_TIMEOUT", "Instagram processing did not finish in time", false);
            }
            Thread.sleep(INSTAGRAM_DRIVE_POLL_INTERVAL.toMillis());
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
