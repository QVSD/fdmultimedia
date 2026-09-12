package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class FfmpegClipExecutorTest {

    @Test
    void buildsControlledCommandWithoutShell() {
        FfmpegClipExecutor executor = new FfmpegClipExecutor("ffmpeg", Duration.ofSeconds(1));
        Path source = Path.of("video.mp4; touch owned");
        Path output = Path.of("clip.mp4");

        List<String> command = executor.command(source, output, 3_000, 5_000);

        assertEquals("ffmpeg", command.get(0));
        assertTrue(command.contains("-ss"));
        assertTrue(command.contains("-t"));
        assertTrue(command.contains("libx264"));
        assertEquals(source.toString(), command.get(command.indexOf("-i") + 1));
        assertEquals(output.toString(), command.get(command.size() - 1));
        assertTrue(command.stream().noneMatch(argument -> argument.equals("sh") || argument.equals("cmd") || argument.equals("powershell")));
    }

    @Test
    void successfulProcessCreatesChecksumWithoutDestroyingChild() throws Exception {
        byte[] outputBytes = "mp4-output".getBytes(StandardCharsets.UTF_8);
        ControllableProcess process = ControllableProcess.completed(new byte[0], new byte[0], 0);
        FfmpegClipExecutor executor = new FfmpegClipExecutor(
                "ffmpeg",
                Duration.ofSeconds(1),
                command -> {
                    Files.write(Path.of(command.get(command.size() - 1)), outputBytes);
                    return process;
                });
        Path source = Files.createTempFile("fdm-test-source-", ".media");
        Path output = Files.createTempFile("fdm-test-output-", ".mp4");
        try {
            Files.writeString(source, "source");

            CreatedClip clip = executor.createClip(source, output, 0, 1_000);

            assertEquals(output, clip.path());
            assertEquals(outputBytes.length, clip.fileSizeBytes());
            assertEquals("video/mp4", clip.contentType());
            assertEquals("mp4", clip.containerFormat());
            assertEquals("74a678c9cdfbe430e9292085f5d5cfb61264942982eb39075971f593d43974f1", clip.checksumSha256());
            assertFalse(process.destroyed());
            assertFalse(process.destroyedForcibly());
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(output);
        }
    }

    @Test
    void timeoutTerminatesChild() throws Exception {
        ControllableProcess process = ControllableProcess.running();
        FfmpegClipExecutor executor = new FfmpegClipExecutor(
                "ffmpeg",
                Duration.ofMillis(25),
                command -> process);
        Path source = Files.createTempFile("fdm-test-source-", ".media");
        Path output = Files.createTempFile("fdm-test-output-", ".mp4");
        try {
            ImportFailureException ex = assertThrows(
                    ImportFailureException.class,
                    () -> executor.createClip(source, output, 0, 1_000));

            assertEquals("FFMPEG_TIMEOUT", ex.code());
            assertTrue(process.destroyed());
            assertFalse(process.isAlive());
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(output);
        }
    }

    @Test
    void ffmpegErrorDoesNotExposeLocalTempPaths() throws Exception {
        Path source = Path.of("C:\\Users\\drago\\AppData\\Local\\Temp\\fdm-clip-source-secret.media");
        Path output = Path.of("C:\\Users\\drago\\AppData\\Local\\Temp\\fdm-clip-output-secret.mp4");
        ControllableProcess process = ControllableProcess.completed(
                new byte[0],
                (source + ": invalid data").getBytes(StandardCharsets.UTF_8),
                1);
        FfmpegClipExecutor executor = new FfmpegClipExecutor(
                "ffmpeg",
                Duration.ofSeconds(1),
                command -> process);

        ImportFailureException ex = assertThrows(
                ImportFailureException.class,
                () -> executor.createClip(source, output, 0, 1_000));

        assertEquals("FFMPEG_FAILED", ex.code());
        assertFalse(ex.getMessage().contains("C:\\Users\\drago"));
        assertTrue(ex.getMessage().contains("<source-file>"));
    }

    private static final class ControllableProcess extends Process {

        private final InputStream stdout;
        private final InputStream stderr;
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean destroyed = new AtomicBoolean(false);
        private final AtomicBoolean destroyedForcibly = new AtomicBoolean(false);
        private final int exitCode;

        static ControllableProcess completed(byte[] stdout, byte[] stderr, int exitCode) {
            ControllableProcess process = new ControllableProcess(stdout, stderr, exitCode);
            process.alive.set(false);
            process.exited.countDown();
            return process;
        }

        static ControllableProcess running() {
            return new ControllableProcess(new byte[0], new byte[0], 0);
        }

        private ControllableProcess(byte[] stdout, byte[] stderr, int exitCode) {
            this.stdout = new ByteArrayInputStream(stdout);
            this.stderr = new ByteArrayInputStream(stderr);
            this.exitCode = exitCode;
        }

        boolean destroyed() {
            return destroyed.get();
        }

        boolean destroyedForcibly() {
            return destroyedForcibly.get();
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("Process is still alive");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed.set(true);
            alive.set(false);
            exited.countDown();
        }

        @Override
        public Process destroyForcibly() {
            destroyedForcibly.set(true);
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }
    }
}
