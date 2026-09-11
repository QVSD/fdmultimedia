package com.fdmultimedia.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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

    private final String ffprobePath;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();

    FfprobeMediaInspector(String ffprobePath) {
        this(ffprobePath, Duration.ofSeconds(30));
    }

    FfprobeMediaInspector(String ffprobePath, Duration timeout) {
        this.ffprobePath = ffprobePath;
        this.timeout = timeout;
    }

    InspectionMetadata inspect(Path mediaFile) throws IOException, InterruptedException, ImportFailureException {
        List<String> command = command(mediaFile);
        Process process = new ProcessBuilder(command).start();
        CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream(), MAX_OUTPUT_BYTES));
        CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(() -> readBounded(process.getErrorStream(), 16 * 1024));
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new ImportFailureException("FFPROBE_TIMEOUT", "FFprobe timed out", false);
        }
        byte[] stdout = awaitOutput(stdoutFuture);
        byte[] stderr = awaitOutput(stderrFuture);
        if (process.exitValue() != 0) {
            String message = new String(stderr, StandardCharsets.UTF_8).trim();
            throw new ImportFailureException("FFPROBE_UNSUPPORTED", message.isBlank() ? "FFprobe could not inspect media" : message, true);
        }
        return parse(new String(stdout, StandardCharsets.UTF_8));
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

    private byte[] awaitOutput(CompletableFuture<byte[]> output) throws IOException, InterruptedException {
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

    private static byte[] readBounded(java.io.InputStream input, int maxBytes) {
        try (input) {
            return input.readNBytes(maxBytes);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
