package com.fdmultimedia.worker;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class FfmpegSupport {

    private FfmpegSupport() {
    }

    static boolean isAvailable(String ffmpegPath) {
        try {
            Process process = new ProcessBuilder(ffmpegPath, "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
