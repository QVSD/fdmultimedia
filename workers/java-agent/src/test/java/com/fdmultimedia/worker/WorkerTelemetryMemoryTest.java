package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerTelemetryMemoryTest {

    @Test
    void reportsMemAvailableNotMemFreeOnLinux(@TempDir Path dir) throws Exception {
        Path meminfo = dir.resolve("meminfo");
        // A healthy busy host: almost no MemFree because the page cache holds 4 GiB that is instantly reclaimable.
        Files.writeString(meminfo, """
                MemTotal:        7921000 kB
                MemFree:          700000 kB
                MemAvailable:    4922000 kB
                Buffers:          100000 kB
                Cached:          4300000 kB
                """);
        assertEquals(4_922_000L * 1024L, WorkerTelemetryCollector.linuxAvailableMemory(meminfo));
    }

    @Test
    void fallsBackWhenTheFileIsMissingOrHasNoMemAvailable(@TempDir Path dir) throws Exception {
        assertNull(WorkerTelemetryCollector.linuxAvailableMemory(dir.resolve("absent")));
        Path old = dir.resolve("old-kernel");
        Files.writeString(old, "MemTotal: 100 kB\nMemFree: 50 kB\n");
        assertNull(WorkerTelemetryCollector.linuxAvailableMemory(old));
        Path garbage = dir.resolve("garbage");
        Files.writeString(garbage, "MemAvailable: lots kB\n");
        assertNull(WorkerTelemetryCollector.linuxAvailableMemory(garbage));
    }
}
