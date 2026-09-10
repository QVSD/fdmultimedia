package com.fdmultimedia.worker;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

final class ImportMediaExecutor {

    private final SourceUrlPolicy urlValidator;

    ImportMediaExecutor() {
        this(new ImportUrlValidator());
    }

    ImportMediaExecutor(SourceUrlPolicy urlValidator) {
        this.urlValidator = urlValidator;
    }

    void execute(WorkerAgentClient client, ClaimedJob job, String machineIdentifier)
            throws IOException, InterruptedException, ImportFailureException {
        ImportMediaAuthorization authorization = client.authorizeImport(job.jobId(), machineIdentifier);
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("fdm-import-", ".media");
            DownloadedMedia media = download(authorization, tempFile);
            client.upload(URI.create(authorization.uploadUrl()), media.path(), media.contentType());
            client.completeImport(job.jobId(), machineIdentifier, authorization, media);
        } finally {
            if (tempFile != null) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    DownloadedMedia download(ImportMediaAuthorization authorization, Path target)
            throws IOException, InterruptedException, ImportFailureException {
        URI uri = urlValidator.validate(authorization.sourceUrl());
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(authorization.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        for (int redirect = 0; redirect <= authorization.maxRedirects(); redirect++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(authorization.readTimeoutSeconds()))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                Optional<String> location = response.headers().firstValue("Location");
                response.body().close();
                if (location.isEmpty() || redirect == authorization.maxRedirects()) {
                    throw new ImportFailureException("TOO_MANY_REDIRECTS", "Source URL redirected too many times", true);
                }
                uri = urlValidator.validate(uri.resolve(location.get()).toString());
                continue;
            }
            if (status >= 500) {
                throw new ImportFailureException("SOURCE_TEMPORARY_FAILURE", "Source server returned HTTP " + status, false);
            }
            if (status < 200 || status >= 300) {
                throw new ImportFailureException("SOURCE_REJECTED", "Source server returned HTTP " + status, true);
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (contentLength > authorization.maxDownloadSizeBytes()) {
                throw new ImportFailureException("MEDIA_TOO_LARGE", "Source media exceeds maximum size", true);
            }
            String contentType = response.headers().firstValue("Content-Type")
                    .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                    .orElse("application/octet-stream");
            validateMediaContentType(contentType);
            String checksum = streamToFile(response.body(), target, authorization.maxDownloadSizeBytes());
            long size = Files.size(target);
            return new DownloadedMedia(
                    target,
                    originalFilename(uri),
                    contentType,
                    size,
                    checksum,
                    containerFormat(contentType));
        }
        throw new ImportFailureException("TOO_MANY_REDIRECTS", "Source URL redirected too many times", true);
    }

    private String streamToFile(InputStream body, Path target, long maxBytes) throws IOException, ImportFailureException {
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (DigestInputStream input = new DigestInputStream(body, digest);
             var output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new ImportFailureException("MEDIA_TOO_LARGE", "Source media exceeds maximum size", true);
                }
                output.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private void validateMediaContentType(String contentType) throws ImportFailureException {
        if (contentType.startsWith("text/")
                || contentType.equals("application/json")
                || contentType.equals("application/xml")
                || contentType.equals("text/html")) {
            throw new ImportFailureException("UNSUPPORTED_MEDIA", "Source did not return media content", true);
        }
    }

    private String originalFilename(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank() || path.endsWith("/")) {
            return null;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() ? null : name;
    }

    private String containerFormat(String contentType) {
        int slash = contentType.indexOf('/');
        return slash < 0 ? contentType : contentType.substring(slash + 1);
    }
}
