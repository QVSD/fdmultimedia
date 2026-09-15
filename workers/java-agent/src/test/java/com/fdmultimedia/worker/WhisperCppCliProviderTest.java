package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhisperCppCliProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsControlledWhisperCppCommandWithoutShell() throws Exception {
        Path model = Files.createFile(tempDir.resolve("ggml-base.bin"));
        WhisperCppCliProvider provider = new WhisperCppCliProvider("whisper-cli", model.toString(), Duration.ofSeconds(1));
        Path audio = Path.of("audio.wav; rm -rf nope");
        Path output = Path.of("out dir", "transcript");

        List<String> command = provider.command(audio, output);

        assertEquals("whisper-cli", command.get(0));
        assertEquals(model.toString(), command.get(command.indexOf("-m") + 1));
        assertEquals(audio.toString(), command.get(command.indexOf("-f") + 1));
        assertEquals("auto", command.get(command.indexOf("-l") + 1));
        assertEquals(output.toString(), command.get(command.indexOf("-of") + 1));
        assertTrue(command.contains("-oj"));
        assertTrue(command.contains("-np"));
        assertTrue(command.stream().noneMatch(argument -> argument.equals("sh") || argument.equals("cmd") || argument.equals("powershell")));
    }

    @Test
    void parsesStructuredWhisperCppJson() throws Exception {
        Path model = Files.createFile(tempDir.resolve("ggml-base.bin"));
        WhisperCppCliProvider provider = new WhisperCppCliProvider("whisper-cli", model.toString(), Duration.ofSeconds(1));

        TranscriptionResult result = provider.parse("""
                {
                  "result": { "language": "ro" },
                  "transcription": [
                    { "offsets": { "from": 0, "to": 1500 }, "text": " Bun venit " },
                    { "offsets": { "from": 1500, "to": 3000 }, "text": " astazi discutam " }
                  ]
                }
                """, authorization(10_000, 10, 100, 1_000));

        assertEquals("ro", result.detectedLanguage());
        assertEquals(2, result.segments().size());
        assertEquals(1500, result.segments().get(0).endMs());
        assertEquals("Bun venit", result.segments().get(0).text());
    }

    @Test
    void requiresModelFileForAvailability() {
        WhisperCppCliProvider provider = new WhisperCppCliProvider(
                "whisper-cli",
                tempDir.resolve("missing.bin").toString(),
                Duration.ofSeconds(1),
                command -> {
                    throw new AssertionError("Command should not run without a model file");
                });

        assertFalse(provider.isAvailable());
    }

    @Test
    void availabilityDrainsHelpOutput() throws Exception {
        Path model = Files.createFile(tempDir.resolve("ggml-base.bin"));
        WhisperCppCliProvider provider = new WhisperCppCliProvider(
                "whisper-cli",
                model.toString(),
                Duration.ofSeconds(1),
                command -> CompletedProcess.success("usage: whisper-cli\n".repeat(2_000), ""));

        assertTrue(provider.isAvailable());
    }

    @Test
    void rejectsMalformedOrOversizedWhisperCppOutput() throws Exception {
        Path model = Files.createFile(tempDir.resolve("ggml-base.bin"));
        WhisperCppCliProvider provider = new WhisperCppCliProvider("whisper-cli", model.toString(), Duration.ofSeconds(1));

        assertThrows(ImportFailureException.class, () -> provider.parse("{\"transcription\": \"nope\"}", authorization(10_000, 10, 100, 1_000)));
        ImportFailureException ex = assertThrows(
                ImportFailureException.class,
                () -> provider.parse("""
                        {"transcription":[{"offsets":{"from":0,"to":1000},"text":"this text is too long"}]}
                        """, authorization(10_000, 10, 4, 1_000)));
        assertEquals("TRANSCRIPTION_OUTPUT_TOO_LARGE", ex.code());
        assertTrue(ex.terminal());
        assertFalse(ex.getMessage().isBlank());
    }

    private TranscriptionAuthorization authorization(long durationMs, int maxSegments, int maxSegmentText, int maxTotalText) {
        return new TranscriptionAuthorization(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "http://minio/source",
                1_000_000,
                5,
                20,
                durationMs,
                "LOCAL_WHISPER_CPP",
                "ggml-base.bin",
                maxSegments,
                maxSegmentText,
                maxTotalText);
    }

    private static final class CompletedProcess extends Process {
        private final byte[] stdout;
        private final byte[] stderr;
        private final int exitCode;

        static CompletedProcess success(String stdout, String stderr) {
            return new CompletedProcess(stdout, stderr, 0);
        }

        private CompletedProcess(String stdout, String stderr, int exitCode) {
            this.stdout = stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            this.stderr = stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            this.exitCode = exitCode;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(stdout);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(stderr);
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
        }

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
