package com.fdmultimedia.worker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

final class FfmpegClipExecutor {

    private static final int MAX_ERROR_BYTES = 32 * 1024;
    private static final String OUTPUT_CONTENT_TYPE = "video/mp4";
    private static final String OUTPUT_CONTAINER = "mp4";

    private final String ffmpegPath;
    private final Duration timeout;
    private final ProcessFactory processFactory;
    private final HttpClient httpClient;

    FfmpegClipExecutor(String ffmpegPath) {
        this(ffmpegPath, Duration.ofMinutes(10));
    }

    FfmpegClipExecutor(String ffmpegPath, Duration timeout) {
        this(ffmpegPath, timeout, command -> new ProcessBuilder(command).start());
    }

    FfmpegClipExecutor(String ffmpegPath, Duration timeout, ProcessFactory processFactory) {
        this.ffmpegPath = ffmpegPath;
        this.timeout = timeout;
        this.processFactory = processFactory;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    void execute(WorkerAgentClient client, ClaimedJob job, String machineIdentifier)
            throws IOException, InterruptedException, ImportFailureException {
        ClipAuthorization authorization = client.authorizeClip(job.jobId(), machineIdentifier);
        Path source = null;
        Path output = null;
        try {
            source = Files.createTempFile("fdm-clip-source-", ".media");
            output = Files.createTempFile("fdm-clip-output-", ".mp4");
            download(authorization, source);
            CreatedClip clip = createClip(source, output, authorization.startMs(), authorization.durationMs());
            client.upload(URI.create(authorization.outputUploadUrl()), clip.path(), clip.contentType());
            client.completeClip(job.jobId(), machineIdentifier, authorization, clip);
        } finally {
            if (source != null) {
                Files.deleteIfExists(source);
            }
            if (output != null) {
                Files.deleteIfExists(output);
            }
        }
    }

    void download(ClipAuthorization authorization, Path target)
            throws IOException, InterruptedException, ImportFailureException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(authorization.sourceDownloadUrl()))
                .timeout(Duration.ofSeconds(authorization.readTimeoutSeconds()))
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new ImportFailureException("CLIP_SOURCE_DOWNLOAD_FAILED", "Source download returned HTTP " + response.statusCode(), false);
        }
        long maxBytes = authorization.maxDownloadSizeBytes();
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (contentLength > maxBytes) {
            response.body().close();
            throw new ImportFailureException("CLIP_SOURCE_TOO_LARGE", "Source download exceeds maximum size", true);
        }
        long size = streamBounded(response.body(), target, maxBytes);
        if (size <= 0) {
            throw new ImportFailureException("CLIP_SOURCE_EMPTY", "Source download was empty", true);
        }
    }

    CreatedClip createClip(Path source, Path output, long startMs, long durationMs)
            throws IOException, InterruptedException, ImportFailureException {
        List<String> command = command(source, output, startMs, durationMs);
        Process process = processFactory.start(command);
        boolean completed = false;
        try {
            CompletableFuture<CapturedOutput> stderrFuture =
                    CompletableFuture.supplyAsync(() -> captureAndDrain(process.getErrorStream(), MAX_ERROR_BYTES));
            CompletableFuture<CapturedOutput> stdoutFuture =
                    CompletableFuture.supplyAsync(() -> captureAndDrain(process.getInputStream(), 1024));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
                throw new ImportFailureException("FFMPEG_TIMEOUT", "FFmpeg timed out", false);
            }
            completed = true;
            CapturedOutput stderr = awaitOutput(stderrFuture);
            awaitOutput(stdoutFuture);
            if (process.exitValue() != 0) {
                String message = sanitizeError(new String(stderr.bytes(), StandardCharsets.UTF_8).trim(), source, output);
                throw new ImportFailureException("FFMPEG_FAILED", message.isBlank() ? "FFmpeg could not create clip" : message, true);
            }
            long size = Files.size(output);
            if (size <= 0) {
                throw new ImportFailureException("FFMPEG_EMPTY_OUTPUT", "FFmpeg created an empty clip", true);
            }
            return new CreatedClip(output, size, sha256(output), OUTPUT_CONTENT_TYPE, OUTPUT_CONTAINER);
        } catch (InterruptedException ex) {
            terminateQuietly(process);
            Thread.currentThread().interrupt();
            throw ex;
        } catch (RuntimeException | IOException | ImportFailureException ex) {
            terminateQuietly(process);
            throw ex;
        } finally {
            if (!completed) {
                terminateQuietly(process);
            }
        }
    }

    List<String> command(Path source, Path output, long startMs, long durationMs) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");
        command.add("-hide_banner");
        command.add("-v");
        command.add("error");
        command.add("-i");
        command.add(source.toString());
        command.add("-ss");
        command.add(seconds(startMs));
        command.add("-t");
        command.add(seconds(durationMs));
        command.add("-map");
        command.add("0:v?");
        command.add("-map");
        command.add("0:a?");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-c:a");
        command.add("aac");
        command.add("-movflags");
        command.add("+faststart");
        command.add(output.toString());
        return command;
    }

    private String seconds(long millis) {
        return BigDecimal.valueOf(millis)
                .divide(BigDecimal.valueOf(1000), 3, RoundingMode.DOWN)
                .stripTrailingZeros()
                .toPlainString();
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
                    throw new ImportFailureException("CLIP_SOURCE_TOO_LARGE", "Source download exceeds maximum size", true);
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return total;
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private CapturedOutput awaitOutput(CompletableFuture<CapturedOutput> output) throws IOException, InterruptedException {
        try {
            return output.get();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException && runtimeException.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Could not read FFmpeg output", cause);
        }
    }

    private void terminate(Process process) throws InterruptedException {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS) && process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    private void terminateQuietly(Process process) {
        boolean interrupted = false;
        try {
            terminate(process);
        } catch (InterruptedException ex) {
            interrupted = true;
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String sanitizeError(String message, Path source, Path output) {
        if (message.isBlank()) {
            return message;
        }
        String sanitized = message.replace(source.toString(), "<source-file>")
                .replace(output.toString(), "<output-file>");
        Path sourceName = source.getFileName();
        if (sourceName != null) {
            sanitized = sanitized.replace(sourceName.toString(), "<source-file>");
        }
        Path outputName = output.getFileName();
        if (outputName != null) {
            sanitized = sanitized.replace(outputName.toString(), "<output-file>");
        }
        return sanitized;
    }

    private static CapturedOutput captureAndDrain(InputStream input, int maxBytes) {
        try (input) {
            ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] buffer = new byte[8192];
            int total = 0;
            boolean truncated = false;
            int read;
            while ((read = input.read(buffer)) != -1) {
                int remaining = maxBytes - total;
                if (remaining > 0) {
                    int toCapture = Math.min(remaining, read);
                    captured.write(buffer, 0, toCapture);
                    total += toCapture;
                    if (toCapture < read) {
                        truncated = true;
                    }
                } else {
                    truncated = true;
                }
            }
            return new CapturedOutput(captured.toByteArray(), truncated);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    interface ProcessFactory {
        Process start(List<String> command) throws IOException;
    }

    record CapturedOutput(byte[] bytes, boolean truncated) {
    }
}
