package com.fdmultimedia.worker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

final class TranscribeMediaExecutor {

    private static final int MAX_ERROR_BYTES = 32 * 1024;

    private final String ffmpegPath;
    private final Duration extractionTimeout;
    private final TranscriptionProvider provider;
    private final ProcessFactory processFactory;
    private final HttpClient httpClient;

    TranscribeMediaExecutor(String ffmpegPath, TranscriptionProvider provider) {
        this(ffmpegPath, Duration.ofMinutes(10), provider, command -> new ProcessBuilder(command).start());
    }

    TranscribeMediaExecutor(String ffmpegPath, Duration extractionTimeout, TranscriptionProvider provider, ProcessFactory processFactory) {
        this.ffmpegPath = ffmpegPath;
        this.extractionTimeout = extractionTimeout;
        this.provider = provider;
        this.processFactory = processFactory;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    void execute(WorkerAgentClient client, ClaimedJob job, String machineIdentifier)
            throws IOException, InterruptedException, ImportFailureException {
        TranscriptionAuthorization authorization = client.authorizeTranscription(job.jobId(), machineIdentifier);
        Path source = null;
        Path audio = null;
        try {
            source = Files.createTempFile("fdm-transcribe-source-", ".media");
            audio = Files.createTempFile("fdm-transcribe-audio-", ".wav");
            download(authorization, source);
            extractAudio(source, audio);
            TranscriptionResult result = provider.transcribe(audio, authorization);
            client.completeTranscription(job.jobId(), machineIdentifier, authorization, result);
        } finally {
            if (source != null) {
                Files.deleteIfExists(source);
            }
            if (audio != null) {
                Files.deleteIfExists(audio);
            }
        }
    }

    void download(TranscriptionAuthorization authorization, Path target)
            throws IOException, InterruptedException, ImportFailureException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(authorization.sourceDownloadUrl()))
                .timeout(Duration.ofSeconds(authorization.readTimeoutSeconds()))
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new ImportFailureException("TRANSCRIPTION_SOURCE_DOWNLOAD_FAILED", "Source download returned HTTP " + response.statusCode(), false);
        }
        long maxBytes = authorization.maxDownloadSizeBytes();
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (contentLength > maxBytes) {
            response.body().close();
            throw new ImportFailureException("TRANSCRIPTION_SOURCE_TOO_LARGE", "Source download exceeds maximum size", true);
        }
        long size = streamBounded(response.body(), target, maxBytes);
        if (size <= 0) {
            throw new ImportFailureException("TRANSCRIPTION_SOURCE_EMPTY", "Source download was empty", true);
        }
    }

    void extractAudio(Path source, Path audio)
            throws IOException, InterruptedException, ImportFailureException {
        runFfmpeg(extractAudioCommand(source, audio), source, audio);
        if (Files.size(audio) <= 0) {
            throw new ImportFailureException("AUDIO_EXTRACTION_EMPTY", "FFmpeg extracted empty audio", true);
        }
    }

    List<String> extractAudioCommand(Path source, Path audio) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");
        command.add("-hide_banner");
        command.add("-v");
        command.add("error");
        command.add("-i");
        command.add(source.toString());
        command.add("-vn");
        command.add("-ac");
        command.add("1");
        command.add("-ar");
        command.add("16000");
        command.add("-c:a");
        command.add("pcm_s16le");
        command.add(audio.toString());
        return command;
    }

    private void runFfmpeg(List<String> command, Path source, Path audio)
            throws IOException, InterruptedException, ImportFailureException {
        Process process = processFactory.start(command);
        boolean completed = false;
        try {
            CompletableFuture<LocalOutput> stdoutFuture =
                    CompletableFuture.supplyAsync(() -> captureAndDrain(process.getInputStream(), 1024));
            CompletableFuture<LocalOutput> stderrFuture =
                    CompletableFuture.supplyAsync(() -> captureAndDrain(process.getErrorStream(), MAX_ERROR_BYTES));
            boolean finished = process.waitFor(extractionTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
                throw new ImportFailureException("AUDIO_EXTRACTION_TIMEOUT", "Audio extraction timed out", false);
            }
            completed = true;
            awaitOutput(stdoutFuture);
            LocalOutput stderr = awaitOutput(stderrFuture);
            if (process.exitValue() != 0) {
                String message = sanitizeError(new String(stderr.bytes(), StandardCharsets.UTF_8).trim(), source, audio);
                throw new ImportFailureException("AUDIO_EXTRACTION_FAILED", message.isBlank() ? "Could not extract audio" : message, true);
            }
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
                    throw new ImportFailureException("TRANSCRIPTION_SOURCE_TOO_LARGE", "Source download exceeds maximum size", true);
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return total;
        }
    }

    private LocalOutput awaitOutput(CompletableFuture<LocalOutput> output) throws IOException, InterruptedException {
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

    private String sanitizeError(String message, Path source, Path audio) {
        if (message.isBlank()) {
            return message;
        }
        String sanitized = message.replace(source.toString(), "<source-file>")
                .replace(audio.toString(), "<audio-file>");
        Path sourceName = source.getFileName();
        if (sourceName != null) {
            sanitized = sanitized.replace(sourceName.toString(), "<source-file>");
        }
        Path audioName = audio.getFileName();
        if (audioName != null) {
            sanitized = sanitized.replace(audioName.toString(), "<audio-file>");
        }
        return sanitized;
    }

    private static LocalOutput captureAndDrain(InputStream input, int maxBytes) {
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
            return new LocalOutput(captured.toByteArray(), truncated);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    interface ProcessFactory {
        Process start(List<String> command) throws IOException;
    }

    record LocalOutput(byte[] bytes, boolean truncated) {
    }
}
