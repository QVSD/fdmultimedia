package com.fdmultimedia.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

final class FfprobeMediaInspector {

    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 16 * 1024;

    private final String ffprobePath;
    private final Duration timeout;
    private final ProcessFactory processFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    FfprobeMediaInspector(String ffprobePath) {
        this(ffprobePath, Duration.ofSeconds(30));
    }

    FfprobeMediaInspector(String ffprobePath, Duration timeout) {
        this(ffprobePath, timeout, command -> new ProcessBuilder(command).start());
    }

    FfprobeMediaInspector(String ffprobePath, Duration timeout, ProcessFactory processFactory) {
        this.ffprobePath = ffprobePath;
        this.timeout = timeout;
        this.processFactory = processFactory;
    }

    InspectionMetadata inspect(Path mediaFile) throws IOException, InterruptedException, ImportFailureException {
        List<String> command = command(mediaFile);
        Process process = processFactory.start(command);
        boolean completed = false;
        try {
            CompletableFuture<CapturedOutput> stdoutFuture = CompletableFuture.supplyAsync(() -> captureAndDrain(process.getInputStream(), MAX_OUTPUT_BYTES));
            CompletableFuture<CapturedOutput> stderrFuture = CompletableFuture.supplyAsync(() -> captureAndDrain(process.getErrorStream(), MAX_ERROR_BYTES));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
                throw new ImportFailureException("FFPROBE_TIMEOUT", "FFprobe timed out", false);
            }
            completed = true;
            CapturedOutput stdout = awaitOutput(stdoutFuture);
            CapturedOutput stderr = awaitOutput(stderrFuture);
            if (process.exitValue() != 0) {
                String message = sanitizeError(new String(stderr.bytes(), StandardCharsets.UTF_8).trim(), mediaFile);
                throw new ImportFailureException("FFPROBE_UNSUPPORTED", message.isBlank() ? "FFprobe could not inspect media" : message, true);
            }
            if (stdout.truncated()) {
                throw new ImportFailureException("FFPROBE_OUTPUT_TOO_LARGE", "FFprobe output exceeded inspection limit", true);
            }
            return parse(new String(stdout.bytes(), StandardCharsets.UTF_8));
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

    List<String> command(Path mediaFile) {
        List<String> command = new ArrayList<>();
        command.add(ffprobePath);
        command.add("-v");
        command.add("error");
        command.add("-print_format");
        command.add("json");
        command.add("-show_format");
        command.add("-show_streams");
        command.add("--");
        command.add(mediaFile.toString());
        return command;
    }

    InspectionMetadata parse(String json) throws ImportFailureException {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode format = root.path("format");
            JsonNode streams = root.path("streams");
            JsonNode video = primaryVideo(streams);
            JsonNode audio = primaryAudio(streams);
            boolean hasVideo = video != null;
            boolean hasAudio = audio != null;
            if (!hasVideo && !hasAudio) {
                throw new ImportFailureException("FFPROBE_NO_MEDIA", "No media streams were found", true);
            }
            return new InspectionMetadata(
                    durationMs(format, video, audio),
                    integer(video, "width"),
                    integer(video, "height"),
                    text(video, "codec_name"),
                    text(audio, "codec_name"),
                    text(format, "format_name"),
                    frameRate(video),
                    longValue(format, "bit_rate"),
                    hasVideo,
                    hasAudio);
        } catch (ImportFailureException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ImportFailureException("FFPROBE_INVALID_OUTPUT", "FFprobe returned invalid JSON", true);
        }
    }

    private JsonNode primaryVideo(JsonNode streams) {
        if (!streams.isArray()) {
            return null;
        }
        for (JsonNode stream : streams) {
            if ("video".equals(text(stream, "codec_type"))
                    && !"1".equals(stream.path("disposition").path("attached_pic").asText())) {
                return stream;
            }
        }
        return null;
    }

    private JsonNode primaryAudio(JsonNode streams) {
        if (!streams.isArray()) {
            return null;
        }
        for (JsonNode stream : streams) {
            if ("audio".equals(text(stream, "codec_type"))) {
                return stream;
            }
        }
        return null;
    }

    private Long durationMs(JsonNode format, JsonNode video, JsonNode audio) {
        BigDecimal seconds = decimalText(format, "duration");
        if (seconds == null) {
            seconds = decimalText(video, "duration");
        }
        if (seconds == null) {
            seconds = decimalText(audio, "duration");
        }
        return seconds == null ? null : seconds.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private BigDecimal frameRate(JsonNode stream) {
        String rate = text(stream, "avg_frame_rate");
        if (rate == null || rate.isBlank() || !rate.contains("/")) {
            return null;
        }
        String[] parts = rate.split("/", 2);
        try {
            BigDecimal numerator = new BigDecimal(parts[0]);
            BigDecimal denominator = new BigDecimal(parts[1]);
            if (denominator.compareTo(BigDecimal.ZERO) == 0 || numerator.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            return numerator.divide(denominator, 3, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer integer(JsonNode node, String field) {
        return node == null || !node.hasNonNull(field) ? null : node.path(field).asInt();
    }

    private Long longValue(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private BigDecimal decimalText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            BigDecimal decimal = new BigDecimal(value);
            return decimal.compareTo(BigDecimal.ZERO) < 0 ? null : decimal;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.path(field).asText();
        return value.isBlank() || "N/A".equals(value) ? null : value;
    }

    private CapturedOutput awaitOutput(CompletableFuture<CapturedOutput> output) throws IOException, InterruptedException {
        try {
            return output.get();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException && runtimeException.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Could not read FFprobe output", cause);
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

    private String sanitizeError(String message, Path mediaFile) {
        if (message.isBlank()) {
            return message;
        }
        String sanitized = message.replace(mediaFile.toString(), "<media-file>");
        Path fileName = mediaFile.getFileName();
        if (fileName != null) {
            sanitized = sanitized.replace(fileName.toString(), "<media-file>");
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
