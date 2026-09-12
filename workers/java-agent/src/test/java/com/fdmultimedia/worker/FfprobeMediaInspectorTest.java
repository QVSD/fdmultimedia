package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FfprobeMediaInspectorTest {

    private final FfprobeMediaInspector inspector = new FfprobeMediaInspector("ffprobe", Duration.ofSeconds(1));

    @Test
    void buildsCommandWithoutShell() {
        Path malicious = Path.of("video.mp4; touch owned");

        List<String> command = inspector.command(malicious);

        assertEquals("ffprobe", command.get(0));
        assertTrue(command.contains("-show_streams"));
        assertTrue(command.contains("--"));
        assertEquals(malicious.toString(), command.get(command.size() - 1));
        assertTrue(command.stream().noneMatch(argument -> argument.equals("sh") || argument.equals("cmd") || argument.equals("powershell")));
    }

    @Test
    void successfulProcessCompletesWithoutDestroyingChild() throws Exception {
        ControllableProcess process = ControllableProcess.completed(validFfprobeJson(), new byte[0]);
        FfprobeMediaInspector localInspector = new FfprobeMediaInspector(
                "ffprobe",
                Duration.ofSeconds(1),
                command -> process);

        InspectionMetadata metadata = localInspector.inspect(Path.of("video.mp4"));

        assertEquals("h264", metadata.videoCodec());
        assertFalse(process.destroyed());
        assertFalse(process.destroyedForcibly());
    }

    @Test
    void interruptTerminatesChildAndPreservesInterruptStatus() throws Exception {
        ControllableProcess process = ControllableProcess.running();
        FfprobeMediaInspector localInspector = new FfprobeMediaInspector(
                "ffprobe",
                Duration.ofSeconds(30),
                command -> process);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptedStatus = new AtomicBoolean(false);

        Thread thread = new Thread(() -> {
            started.countDown();
            try {
                localInspector.inspect(Path.of("video.mp4"));
            } catch (Throwable ex) {
                interruptedStatus.set(Thread.currentThread().isInterrupted());
                thrown.set(ex);
            }
        });
        thread.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));

        thread.interrupt();
        thread.join(2_000);

        assertFalse(thread.isAlive());
        assertTrue(thrown.get() instanceof InterruptedException);
        assertTrue(interruptedStatus.get());
        assertTrue(process.destroyed());
        assertFalse(process.isAlive());
    }

    @Test
    void timeoutTerminatesChild() throws Exception {
        ControllableProcess process = ControllableProcess.running();
        FfprobeMediaInspector localInspector = new FfprobeMediaInspector(
                "ffprobe",
                Duration.ofMillis(25),
                command -> process);

        ImportFailureException ex = assertThrows(
                ImportFailureException.class,
                () -> localInspector.inspect(Path.of("video.mp4")));

        assertEquals("FFPROBE_TIMEOUT", ex.code());
        assertTrue(process.destroyed());
        assertFalse(process.isAlive());
    }

    @Test
    void excessiveStdoutIsDrainedButRejectedAsTooLarge() {
        byte[] hugeOutput = new byte[1024 * 1024 + 10];
        hugeOutput[0] = '{';
        hugeOutput[hugeOutput.length - 1] = '}';
        ControllableProcess process = ControllableProcess.completed(hugeOutput, new byte[0]);
        FfprobeMediaInspector localInspector = new FfprobeMediaInspector(
                "ffprobe",
                Duration.ofSeconds(1),
                command -> process);

        ImportFailureException ex = assertThrows(
                ImportFailureException.class,
                () -> localInspector.inspect(Path.of("video.mp4")));

        assertEquals("FFPROBE_OUTPUT_TOO_LARGE", ex.code());
        assertFalse(process.destroyedForcibly());
    }

    @Test
    void ffprobeErrorDoesNotExposeLocalMediaPath() {
        Path mediaPath = Path.of("C:\\Users\\drago\\AppData\\Local\\Temp\\fdm-inspect-secret.media");
        ControllableProcess process = ControllableProcess.completed(
                new byte[0],
                ("C:\\Users\\drago\\AppData\\Local\\Temp\\fdm-inspect-secret.media: Invalid data found when processing input")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                1);
        FfprobeMediaInspector localInspector = new FfprobeMediaInspector(
                "ffprobe",
                Duration.ofSeconds(1),
                command -> process);

        ImportFailureException ex = assertThrows(
                ImportFailureException.class,
                () -> localInspector.inspect(mediaPath));

        assertEquals("FFPROBE_UNSUPPORTED", ex.code());
        assertFalse(ex.getMessage().contains("C:\\Users\\drago"));
        assertTrue(ex.getMessage().contains("<media-file>"));
    }

    @Test
    void parsesVideoAndAudioMetadata() throws Exception {
        InspectionMetadata metadata = inspector.parse("""
                {
                  "streams": [
                    {"codec_type":"video","codec_name":"h264","width":1920,"height":1080,"avg_frame_rate":"30000/1001","duration":"12.345","disposition":{"attached_pic":0}},
                    {"codec_type":"audio","codec_name":"aac","duration":"12.000"}
                  ],
                  "format": {"duration":"12.345","format_name":"mov,mp4,m4a,3gp,3g2,mj2","bit_rate":"800000"}
                }
                """);

        assertEquals(12_345L, metadata.durationMs());
        assertEquals(1920, metadata.width());
        assertEquals(1080, metadata.height());
        assertEquals("h264", metadata.videoCodec());
        assertEquals("aac", metadata.audioCodec());
        assertEquals(new BigDecimal("29.970"), metadata.frameRate());
        assertEquals(800_000L, metadata.bitrate());
        assertEquals(true, metadata.hasVideo());
        assertEquals(true, metadata.hasAudio());
    }

    @Test
    void ignoresAttachedPictureForPrimaryVideoAndAllowsAudioOnly() throws Exception {
        InspectionMetadata metadata = inspector.parse("""
                {
                  "streams": [
                    {"codec_type":"video","codec_name":"mjpeg","width":600,"height":600,"disposition":{"attached_pic":1}},
                    {"codec_type":"audio","codec_name":"mp3","duration":"3.5"}
                  ],
                  "format": {"format_name":"mp3"}
                }
                """);

        assertNull(metadata.width());
        assertNull(metadata.videoCodec());
        assertEquals("mp3", metadata.audioCodec());
        assertEquals(3_500L, metadata.durationMs());
        assertEquals(false, metadata.hasVideo());
        assertEquals(true, metadata.hasAudio());
    }

    @Test
    void handlesMalformedFrameRateAndInvalidOutput() throws Exception {
        InspectionMetadata metadata = inspector.parse("""
                {"streams":[{"codec_type":"video","codec_name":"h264","avg_frame_rate":"0/0"}],"format":{}}
                """);

        assertNull(metadata.frameRate());
        assertThrows(ImportFailureException.class, () -> inspector.parse("{not json"));
        assertThrows(ImportFailureException.class, () -> inspector.parse("""
                {"streams":[],"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2"}}
                """));
    }

    private byte[] validFfprobeJson() {
        return """
                {
                  "streams": [
                    {"codec_type":"video","codec_name":"h264","width":640,"height":360,"avg_frame_rate":"30/1","disposition":{"attached_pic":0}}
                  ],
                  "format": {"duration":"1.000","format_name":"mp4","bit_rate":"1000"}
                }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final class ControllableProcess extends Process {

        private final InputStream stdout;
        private final InputStream stderr;
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean destroyed = new AtomicBoolean(false);
        private final AtomicBoolean destroyedForcibly = new AtomicBoolean(false);
        private final int exitCode;

        static ControllableProcess completed(byte[] stdout, byte[] stderr) {
            return completed(stdout, stderr, 0);
        }

        static ControllableProcess completed(byte[] stdout, byte[] stderr, int exitCode) {
            ControllableProcess process = new ControllableProcess(stdout, stderr, exitCode);
            process.alive.set(false);
            process.exited.countDown();
            return process;
        }

        static ControllableProcess completed(String stdout, String stderr) {
            return completed(
                    stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
