package com.fdmultimedia.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

final class WhisperCppCliProvider implements TranscriptionProvider {

    private static final int MAX_STDOUT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 32 * 1024;

    private final String commandPath;
    private final String modelPath;
    private final Duration timeout;
    private final ProcessFactory processFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    WhisperCppCliProvider(String commandPath, String modelPath, Duration timeout) {
        this(commandPath, modelPath, timeout, command -> new ProcessBuilder(command).start());
    }

    WhisperCppCliProvider(String commandPath, String modelPath, Duration timeout, ProcessFactory processFactory) {
        this.commandPath = commandPath;
        this.modelPath = modelPath;
        this.timeout = timeout;
        this.processFactory = processFactory;
    }

    @Override
    public boolean isAvailable() {
        if (!Files.isRegularFile(Path.of(modelPath))) {
            return false;
        }
        Process process = null;
        try {
            process = processFactory.start(List.of(commandPath, "--help"));
            Process runningProcess = process;
            CompletableFuture<CapturedOutput> stdoutFuture = CompletableFuture.supplyAsync(() -> captureAndDrain(runningProcess.getInputStream(), 1024));
            CompletableFuture<CapturedOutput> stderrFuture = CompletableFuture.supplyAsync(() -> captureAndDrain(runningProcess.getErrorStream(), 1024));
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                terminateQuietly(process);
                return false;
            }
            awaitOutput(stdoutFuture);
            awaitOutput(stderrFuture);
            return process.exitValue() == 0;
        } catch (InterruptedException ex) {
            terminateQuietly(process);
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Override
    public String providerName() {
        return "LOCAL_WHISPER_CPP";
    }

    @Override
    public String modelName() {
        Path model = Path.of(modelPath).getFileName();
        return model == null ? modelPath : model.toString();
    }

    @Override
    public TranscriptionResult transcribe(Path audioFile, TranscriptionAuthorization authorization)
            throws IOException, InterruptedException, ImportFailureException {
        Path outputDir = Files.createTempDirectory("fdm-transcribe-output-");
        try {
            Path outputBase = outputDir.resolve("transcript");
            runWhisper(command(audioFile, outputBase), audioFile, outputDir);
            Path json = Path.of(outputBase + ".json");
            if (!Files.isRegularFile(json)) {
                throw new ImportFailureException("TRANSCRIPTION_OUTPUT_MISSING", "Transcription provider did not produce JSON", true);
            }
            if (Files.size(json) > MAX_STDOUT_BYTES) {
                throw new ImportFailureException("TRANSCRIPTION_OUTPUT_TOO_LARGE", "Transcription output exceeded limit", true);
            }
            return parse(Files.readString(json, StandardCharsets.UTF_8), authorization);
        } finally {
            deleteTree(outputDir);
        }
    }

    List<String> command(Path audioFile, Path outputBase) {
        List<String> command = new ArrayList<>();
        command.add(commandPath);
        command.add("-m");
        command.add(modelPath);
        command.add("-f");
        command.add(audioFile.toString());
        command.add("-l");
        command.add("auto");
        command.add("-oj");
        command.add("-of");
        command.add(outputBase.toString());
        command.add("-np");
        return command;
    }

    TranscriptionResult parse(String json, TranscriptionAuthorization authorization) throws ImportFailureException {
        try {
            JsonNode root = objectMapper.readTree(json);
            String language = text(root.path("result"), "language");
            JsonNode transcriptionNode = root.path("transcription");
            if (!transcriptionNode.isArray()) {
                throw new ImportFailureException("TRANSCRIPTION_INVALID_OUTPUT", "Transcription output has no segments", true);
            }
            List<TranscriptSegmentResult> segments = new ArrayList<>();
            int totalText = 0;
            for (JsonNode node : transcriptionNode) {
                long startMs = node.path("offsets").path("from").asLong(-1);
                long endMs = node.path("offsets").path("to").asLong(-1);
                String text = text(node, "text");
                if (text == null) {
                    text = "";
                }
                text = text.trim().replaceAll("\\s+", " ");
                totalText += text.length();
                if (segments.size() >= authorization.maxSegments()
                        || text.length() > authorization.maxSegmentTextLength()
                        || totalText > authorization.maxTotalTextLength()) {
                    throw new ImportFailureException("TRANSCRIPTION_OUTPUT_TOO_LARGE", "Transcription output exceeded limits", true);
                }
                segments.add(new TranscriptSegmentResult(startMs, endMs, text, null));
            }
            if (segments.isEmpty()) {
                throw new ImportFailureException("TRANSCRIPTION_EMPTY", "No speech segments were detected", true);
            }
            return new TranscriptionResult(language, authorization.sourceDurationMs(), segments);
        } catch (ImportFailureException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ImportFailureException("TRANSCRIPTION_INVALID_OUTPUT", "Transcription provider returned invalid JSON", true);
        }
    }

    private void runWhisper(List<String> command, Path audioFile, Path outputDir)
            throws IOException, InterruptedException, ImportFailureException {
        Process process = processFactory.start(command);
        boolean completed = false;
        try {
            CompletableFuture<CapturedOutput> stdoutFuture = CompletableFuture.supplyAsync(() -> captureAndDrain(process.getInputStream(), MAX_STDOUT_BYTES));
            CompletableFuture<CapturedOutput> stderrFuture = CompletableFuture.supplyAsync(() -> captureAndDrain(process.getErrorStream(), MAX_ERROR_BYTES));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
                throw new ImportFailureException("TRANSCRIPTION_TIMEOUT", "Transcription timed out", false);
            }
            completed = true;
            CapturedOutput stdout = awaitOutput(stdoutFuture);
            CapturedOutput stderr = awaitOutput(stderrFuture);
            if (stdout.truncated()) {
                throw new ImportFailureException("TRANSCRIPTION_OUTPUT_TOO_LARGE", "Transcription output exceeded limit", true);
            }
            if (process.exitValue() != 0) {
                String message = sanitizeError(new String(stderr.bytes(), StandardCharsets.UTF_8).trim(), audioFile, outputDir);
                throw new ImportFailureException("TRANSCRIPTION_FAILED", message.isBlank() ? "Transcription provider failed" : message, true);
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

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private CapturedOutput awaitOutput(CompletableFuture<CapturedOutput> output) throws IOException, InterruptedException {
        try {
            return output.get();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException && runtimeException.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Could not read transcription provider output", cause);
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

    private String sanitizeError(String message, Path audioFile, Path outputDir) {
        if (message.isBlank()) {
            return message;
        }
        String sanitized = message.replace(audioFile.toString(), "<audio-file>")
                .replace(outputDir.toString(), "<output-dir>")
                .replace(modelPath, "<model-file>");
        Path audioName = audioFile.getFileName();
        if (audioName != null) {
            sanitized = sanitized.replace(audioName.toString(), "<audio-file>");
        }
        Path modelName = Path.of(modelPath).getFileName();
        if (modelName != null) {
            sanitized = sanitized.replace(modelName.toString(), "<model-file>");
        }
        return sanitized;
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        }
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
